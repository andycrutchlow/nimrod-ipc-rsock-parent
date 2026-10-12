package com.nimrodtechs.ipcrsock.subscriber;

import com.nimrodtechs.ipcrsock.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.RSocketStrategies;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

@Service
public class SubscriberService {
    private static final Logger log = LoggerFactory.getLogger(SubscriberService.class);

    @Value("${spring.application.name:#{null}}")
    String subscriberProcessName;

    @Value("${nimrod.rsock.subscriberName:#{null}}")
    String subscriberProcessNameOverride;

    @Autowired
    private SubscriberProperties subscriberProperties;

    @Autowired
    RSocketStrategies rSocketStrategies;

    // Guards lifecycle state only. Message dispatch continues using concurrent maps/list snapshots.
    private final Object lifecycleLock = new Object();
    private final Map<String, SubscriberConnectionInfo> subscriberInfoMap = new HashMap<>();

    Map<String, SubscriptionInfo> clientSubscriptions = new HashMap<>();

    static class SubscriptionInfo<T> {
        String subject;
        Class<T> payloadClass;
        SubscriptionRequest originalSubscriptionRequest;
        Disposable disposable;
        Object streamAttempt;

        public SubscriptionInfo(String subject, Class<T> payloadClass, SubscriptionRequest originalSubscriptionRequest) {
            this.subject = subject;
            this.payloadClass = payloadClass;
            this.originalSubscriptionRequest = originalSubscriptionRequest;
        }

        public Disposable getDisposable() {
            return disposable;
        }

        public void setDisposable(Disposable disposable) {
            this.disposable = disposable;
        }
    }

    static class LocalSubscription {
        final String subject;
        final boolean wildcard;
        final List<MessageProcessorEntry> listeners = new CopyOnWriteArrayList<>();
        Class<?> payloadClass;

        LocalSubscription(String subject, boolean wildcard) {
            this.subject = subject;
            this.wildcard = wildcard;
        }
    }

    static class SubscriberConnectionInfo {
        private String name;
        private String host;
        private int port;

        Map<String, LocalSubscription> subjectListeners = new ConcurrentHashMap<>();
        Map<String, LocalSubscription> wildcardListeners = new ConcurrentHashMap<>();

        /**
         * Full subject strings currently active upstream, e.g.
         *   aaa.bbb
         *   aaa.*
         */
        Set<String> upstreamActiveSubjects = new HashSet<>();

        QueueExecutor conflatingQueueExecutor;
        QueueExecutor sequentialQueueExecutor;

        private RSocketRequester rSocketRequester;

        Timer reSubscribeTimer;
        TimerTask reSubscribeTimerTask;

        public SubscriberConnectionInfo(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
            conflatingQueueExecutor = new ConflatingExecutor(name);
            sequentialQueueExecutor = new SequentialExecutor(name);
            reSubscribeTimer = new Timer(name + "-reconnect-task", true);
        }

        String getName() {
            return name;
        }

        String getHost() {
            return host;
        }

        int getPort() {
            return port;
        }

        void setrSocketRequester(RSocketRequester rSocketRequester) {
            this.rSocketRequester = rSocketRequester;
        }

        RSocketRequester getRSocketRequester() {
            return rSocketRequester;
        }
    }

    private static final Pattern SUBJECT_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_:-]+(->[a-zA-Z0-9_:-]+)?(\\.[a-zA-Z0-9_:-]+)*(\\.\\*)?$");

    private void validateSubject(String subject) throws NimrodPubSubException {
        if (!SUBJECT_PATTERN.matcher(subject).matches()) {
            throw new NimrodPubSubException(
                    "Invalid subject pattern: " + subject +
                            ". Valid examples: aaa, aaa.bbb, aaa.*, aaa.bbb.*"
            );
        }
    }

    private boolean wildcardCovers(String wildcardSubject, String subject) {
        String prefix = wildcardSubject.replace("*", "");
        return subject.length() > prefix.length() && subject.startsWith(prefix);
    }

    private LocalSubscription getOrCreateLocalSubscription(
            SubscriberConnectionInfo subscriberConnectionInfo,
            String subject,
            boolean wildcard
    ) {
        String key = wildcard ? subject.replace("*", "") : subject;
        Map<String, LocalSubscription> map =
                wildcard ? subscriberConnectionInfo.wildcardListeners
                        : subscriberConnectionInfo.subjectListeners;

        return map.computeIfAbsent(key, k -> new LocalSubscription(subject, wildcard));
    }

    private LocalSubscription findLocalSubscription(
            SubscriberConnectionInfo subscriberConnectionInfo,
            String subject
    ) {
        if (subject.endsWith("*")) {
            return subscriberConnectionInfo.wildcardListeners.get(subject.replace("*", ""));
        }
        return subscriberConnectionInfo.subjectListeners.get(subject);
    }

    private <T> void activateUpstream(
            String publisherName,
            SubscriberConnectionInfo subscriberConnectionInfo,
            String subject,
            boolean wildcard,
            Class<T> payloadClass
    ) {
        if (subscriberConnectionInfo.upstreamActiveSubjects.contains(subject)) {
            return;
        }

        SubscriptionRequest subscriptionRequest =
                new SubscriptionRequest(
                        SubscriptionDirective.REQUEST,
                        subscriberProcessName,
                        subject,
                        wildcard
                );

        SubscriptionInfo<T> subscriptionInfo =
                new SubscriptionInfo<>(subject, payloadClass, subscriptionRequest);

        clientSubscriptions.put(publisherName + ":" + subject, subscriptionInfo);
        subscriberConnectionInfo.upstreamActiveSubjects.add(subject);
        try {
            installFlux(subscriberConnectionInfo, subscriptionInfo);
        } catch (RuntimeException ex) {
            retireSubscription(subscriptionInfo);
            clientSubscriptions.remove(publisherName + ":" + subject, subscriptionInfo);
            subscriberConnectionInfo.upstreamActiveSubjects.remove(subject);
            throw ex;
        }

        log.info("SUBSCRIBED TO:{} subject[{}]", publisherName, subject);
    }

    private void deactivateUpstream(
            String publisherName,
            SubscriberConnectionInfo subscriberConnectionInfo,
            String subject
    ) {
        if (!subscriberConnectionInfo.upstreamActiveSubjects.contains(subject)) {
            return;
        }

        SubscriptionInfo subscriptionInfo =
                clientSubscriptions.remove(publisherName + ":" + subject);
        subscriberConnectionInfo.upstreamActiveSubjects.remove(subject);
        if (subscriptionInfo != null) {
            retireSubscription(subscriptionInfo);
        }

        try {
            RSocketRequester.RequestSpec requestSpec =
                    subscriberConnectionInfo.getRSocketRequester().route(publisherName);

            requestSpec.data(
                            new SubscriptionRequest(
                                    SubscriptionDirective.CANCEL,
                                    subscriberProcessName,
                                    subject
                            )
                    )
                    .send()
                    .subscribe(null, e -> log.error("Failed sending CANCEL for {} {}", publisherName, subject, e));
        } catch (RuntimeException ex) {
            log.error("Failed sending CANCEL for {} {}", publisherName, subject, ex);
        }

        log.info("UNSUBSCRIBE FROM:{} subject[{}]", publisherName, subject);
    }

    /**
     * Recompute the minimal upstream subscription set from local subscriptions.
     *
     * Rules:
     * - Only the broadest necessary trailing-wildcard subscriptions
     *   are maintained upstream.
     * - Narrower wildcard subscriptions covered by broader wildcards
     *   remain registered locally but are suppressed upstream.
     * - Exact subscriptions covered by active local wildcards are
     *   also suppressed upstream.
     * - Removing broader coverage reactivates any uncovered narrower
     *   wildcard or exact subscriptions.
     */
    @SuppressWarnings("unchecked")
    private void recomputeUpstreamSubscriptions(String publisherName) {
        SubscriberConnectionInfo sci = subscriberInfoMap.get(publisherName);
        if (sci == null) {
            return;
        }

        Set<String> desired = new HashSet<>();

        // 1. Only activate wildcards that are not covered by a broader wildcard.
        for (LocalSubscription sub : sci.wildcardListeners.values()) {
            if (sub.listeners.isEmpty()) {
                continue;
            }

            boolean covered = sci.wildcardListeners.values().stream()
                    .filter(w -> w != sub && !w.listeners.isEmpty())
                    .anyMatch(w -> wildcardCovers(w.subject, sub.subject));

            if (!covered) {
                desired.add(sub.subject);
            }
        }

        // 2. Exact subjects with listeners are desired only if not covered by any wildcard
        for (LocalSubscription sub : sci.subjectListeners.values()) {
            if (sub.listeners.isEmpty()) {
                continue;
            }

            boolean covered =
                    sci.wildcardListeners.values().stream()
                            .filter(w -> !w.listeners.isEmpty())
                            .anyMatch(w -> wildcardCovers(w.subject, sub.subject));

            if (!covered) {
                desired.add(sub.subject);
            }
        }

        Set<String> toAdd = new HashSet<>(desired);
        toAdd.removeAll(sci.upstreamActiveSubjects);

        Set<String> toRemove = new HashSet<>(sci.upstreamActiveSubjects);
        toRemove.removeAll(desired);

        for (String subject : toRemove) {
            deactivateUpstream(publisherName, sci, subject);
        }

        for (String subject : toAdd) {
            LocalSubscription sub = findLocalSubscription(sci, subject);
            if (sub == null) {
                log.warn("Unable to find local subscription for subject [{}] while recomputing upstream subscriptions", subject);
                continue;
            }

            if (sub.payloadClass == null) {
                log.warn("No payloadClass recorded for subject [{}] while recomputing upstream subscriptions", subject);
                continue;
            }

            activateUpstream(
                    publisherName,
                    sci,
                    sub.subject,
                    sub.wildcard,
                    (Class<Object>) sub.payloadClass
            );
        }
        if (sci.upstreamActiveSubjects.isEmpty()) {
            cancelRecovery(sci);
        }
    }

    // Called with lifecycleLock held. Invalidate callbacks before cancelling their stream.
    private void retireSubscription(SubscriptionInfo subscriptionInfo) {
        subscriptionInfo.streamAttempt = null;
        Disposable disposable = subscriptionInfo.getDisposable();
        subscriptionInfo.setDisposable(null);
        disposeSafely(disposable);
    }

    private void disposeSafely(Disposable disposable) {
        if (disposable != null) {
            try {
                disposable.dispose();
            } catch (RuntimeException ex) {
                log.warn("Failed disposing subscriber resource", ex);
            }
        }
    }

    // Subscribe and retain its handle in the same lifecycle transaction as unsubscribe.
    private void installFlux(SubscriberConnectionInfo sci, SubscriptionInfo subscriptionInfo) {
        Object attempt = new Object();
        subscriptionInfo.streamAttempt = attempt;
        Disposable disposable = establishFlux(sci, subscriptionInfo, sci.getRSocketRequester(), attempt);
        // Also defend against synchronous, reentrant lifecycle changes during subscription setup.
        if (subscriberInfoMap.get(sci.getName()) == sci
                && clientSubscriptions.get(sci.getName() + ":" + subscriptionInfo.subject) == subscriptionInfo
                && subscriptionInfo.streamAttempt == attempt) {
            subscriptionInfo.setDisposable(disposable);
        } else {
            disposeSafely(disposable);
        }
    }

    private void cancelRecovery(SubscriberConnectionInfo sci) {
        TimerTask task = sci.reSubscribeTimerTask;
        sci.reSubscribeTimerTask = null;
        if (task != null) {
            task.cancel();
        }
    }

    private void scheduleRecovery(SubscriberConnectionInfo sci) {
        if (subscriberInfoMap.get(sci.getName()) != sci
                || sci.upstreamActiveSubjects.isEmpty()
                || sci.reSubscribeTimerTask != null) {
            return;
        }
        ReSubscribeTask task = new ReSubscribeTask(sci);
        sci.reSubscribeTimerTask = task;
        try {
            sci.reSubscribeTimer.schedule(task, 2000);
        } catch (RuntimeException ex) {
            if (sci.reSubscribeTimerTask == task) {
                sci.reSubscribeTimerTask = null;
            }
            task.cancel();
            log.error("Failed scheduling recovery for publisher {}", sci.getName(), ex);
        }
    }

    class ReSubscribeTask extends TimerTask {

        private final SubscriberConnectionInfo subscriberConnectionInfo;
        public ReSubscribeTask(SubscriberConnectionInfo subscriberConnectionInfo) {
            this.subscriberConnectionInfo = subscriberConnectionInfo;
        }

        private boolean ownsRecovery() {
            return subscriberInfoMap.get(subscriberConnectionInfo.getName()) == subscriberConnectionInfo
                    && subscriberConnectionInfo.reSubscribeTimerTask == this
                    && !subscriberConnectionInfo.upstreamActiveSubjects.isEmpty();
        }

        @Override
        public void run() {

            RSocketRequester candidate = null;
            RSocketRequester previous = null;
            boolean retry = false;
            try {
                synchronized (lifecycleLock) {
                    if (!ownsRecovery()) {
                        return;
                    }
                    log.info("Try to resubscribe on publisher [{}] subscriptions [{}]",
                            subscriberConnectionInfo.getName(),
                            String.join(",", subscriberConnectionInfo.upstreamActiveSubjects));
                }

                // Requester construction is lazy, but keep it outside the shared lifecycle lock.
                candidate = getRSocketRequester(subscriberConnectionInfo);

                synchronized (lifecycleLock) {
                    if (!ownsRecovery()) {
                        return;
                    }
                    previous = subscriberConnectionInfo.getRSocketRequester();
                    subscriberConnectionInfo.setrSocketRequester(candidate);
                    candidate = null;

                    // Retire all old attempts before installing any replacement streams.
                    for (String subject : subscriberConnectionInfo.upstreamActiveSubjects) {
                        SubscriptionInfo subscriptionInfo = clientSubscriptions.get(
                                subscriberConnectionInfo.getName() + ":" + subject);
                        if (subscriptionInfo != null) {
                            retireSubscription(subscriptionInfo);
                        }
                    }
                    for (String subject : subscriberConnectionInfo.upstreamActiveSubjects) {
                        SubscriptionInfo subscriptionInfo = clientSubscriptions.get(
                                subscriberConnectionInfo.getName() + ":" + subject);
                        if (subscriptionInfo != null) {
                            installFlux(subscriberConnectionInfo, subscriptionInfo);
                        }
                    }
                }
            } catch (RuntimeException ex) {
                retry = true;
                log.error("Failed resubscribing on publisher {}", subscriberConnectionInfo.getName(), ex);
            } finally {
                synchronized (lifecycleLock) {
                    if (subscriberConnectionInfo.reSubscribeTimerTask == this) {
                        subscriberConnectionInfo.reSubscribeTimerTask = null;
                        if (retry) {
                            scheduleRecovery(subscriberConnectionInfo);
                        }
                    }
                }
                disposeSafely(candidate);
                disposeSafely(previous);
            }
        }
    }

    @PostConstruct
    void init() throws NimrodPubSubException {
        if (subscriberProperties.getSetup() == null) {
            // Quietly return and don't attempt to set up subscriber connections or
            // assume that subscriber connections will be set up programmatically.
            return;
        }

        String processName;
        synchronized (lifecycleLock) {
            if (subscriberProcessNameOverride != null) {
                subscriberProcessName = subscriberProcessNameOverride;
            }

            if (subscriberProcessName == null) {
                log.error("If subscriberProperties are provided then property or VM param spring.application.name must be supplied");
                throw new NimrodPubSubException(
                        "If subscriberProperties are provided then property or VM param spring.application.name must be supplied"
                );
            }
            //Need to ensure the resulting subscriberProcessName is unique across all subscribers, even if they have the same logical name e.g. pod replicas
            subscriberProcessName = subscriberProcessName + "-" + UUID.randomUUID().toString();
            log.info("subscriberProcessName={}", subscriberProcessName);
            processName = subscriberProcessName;
        }

        for (String subscriberInfoItems : subscriberProperties.getSetup()) {
            String[] items = subscriberInfoItems.split(",");
            addSubscriberSocket(processName, items[0], items[1], Integer.valueOf(items[2]));
        }
    }

    @PreDestroy
    void destroy() {
        synchronized (lifecycleLock) {
            log.info("Shutdown : subscriberInfoMap size={}", subscriberInfoMap.size());
        }
    }

    public void addSubscriberSocket(String subscriberNameOnDemand, String name, String host, int port) throws NimrodPubSubException {
        synchronized (lifecycleLock) {
            if (subscriberProcessName == null) {
                subscriberProcessName = subscriberNameOnDemand;
            } else {
                if (!subscriberProcessName.equals(subscriberNameOnDemand)) {
                    throw new NimrodPubSubException(
                            "You cannot change subscriberProcessName[" + subscriberProcessName + "] to [" + subscriberNameOnDemand + "]"
                    );
                }
            }
        }

        SubscriberConnectionInfo subscriberConnectionInfo = new SubscriberConnectionInfo(name, host, port);
        RSocketRequester requester;
        try {
            requester = getRSocketRequester(subscriberConnectionInfo);
        } catch (RuntimeException ex) {
            subscriberConnectionInfo.reSubscribeTimer.cancel();
            throw ex;
        }
        RSocketRequester previous = null;
        synchronized (lifecycleLock) {
            subscriberConnectionInfo.setrSocketRequester(requester);
            SubscriberConnectionInfo old = subscriberInfoMap.put(name, subscriberConnectionInfo);
            if (old != null) {
                cancelRecovery(old);
                old.reSubscribeTimer.cancel();
                for (String subject : old.upstreamActiveSubjects) {
                    SubscriptionInfo subscriptionInfo = clientSubscriptions.remove(name + ":" + subject);
                    if (subscriptionInfo != null) {
                        retireSubscription(subscriptionInfo);
                    }
                }
                old.upstreamActiveSubjects.clear();
                previous = old.getRSocketRequester();
            }
        }
        disposeSafely(previous);
    }

    private RSocketRequester getRSocketRequester(SubscriberConnectionInfo subscriberConnectionInfo) {

        RSocketRequester.Builder builder = RSocketRequester.builder();

        // TODO make keepAlive settings parameters ... need long max duration when debugging
        RSocketRequester rSocketRequester = builder
                .rsocketConnector(
                        rSocketConnector -> {
                            rSocketConnector.keepAlive(Duration.ofSeconds(90), Duration.ofSeconds(7200));
                            rSocketConnector.reconnect(
                                    Retry.fixedDelay(Long.MAX_VALUE, Duration.ofSeconds(2))
                            );
                        })
                .rsocketStrategies(rSocketStrategies)
                .dataMimeType(new MimeType("application", "x-kryo"))
                .tcp(subscriberConnectionInfo.getHost(), subscriberConnectionInfo.getPort());

        log.info(
                "Configured Nimrod RSocket Subscriber for {} on host {} port {}",
                subscriberConnectionInfo.getName(),
                subscriberConnectionInfo.getHost(),
                subscriberConnectionInfo.getPort()
        );

        return rSocketRequester;
    }

    public <T> void subscribe(
            String publisherName,
            String aSubject,
            MessageReceiverInterface<T> listener,
            Class<T> payloadClass,
            boolean conflate
    ) throws NimrodPubSubException {
        synchronized (lifecycleLock) {
            SubscriberConnectionInfo subscriberConnectionInfo = subscriberInfoMap.get(publisherName);
            if (subscriberConnectionInfo == null) {
                throw new NimrodPubSubException(
                        publisherName + " is not a valid publisher to send subscribe " + aSubject + " to"
                );
            }

            validateSubject(aSubject);

            boolean wildcard = aSubject.endsWith("*");

            LocalSubscription localSubscription =
                    getOrCreateLocalSubscription(subscriberConnectionInfo, aSubject, wildcard);

            if (localSubscription.listeners.stream().anyMatch(entry -> entry.messageReceiver == listener)) {
                log.info("{} subject[{}] listener {} already present : IGNORE", publisherName, aSubject, listener);
                return;
            }

            if (!localSubscription.listeners.isEmpty()) {
                QueueExecutor existingExecutor = localSubscription.listeners.get(0).queueExecutor;

                boolean sameType =
                        (existingExecutor instanceof ConflatingExecutor && conflate)
                                || (existingExecutor instanceof SequentialExecutor && !conflate);

                if (!sameType) {
                    log.info(
                            "{} subject[{}] adding another listener BUT cannot have different type of QueueExecutor : IGNORE",
                            publisherName, aSubject
                    );
                    return;
                }
            }

            MessageProcessorEntry messageProcessorEntry =
                    new MessageProcessorEntry(
                            listener,
                            conflate ? subscriberConnectionInfo.conflatingQueueExecutor
                                    : subscriberConnectionInfo.sequentialQueueExecutor
                    );

            localSubscription.listeners.add(messageProcessorEntry);
            localSubscription.payloadClass = payloadClass;

            recomputeUpstreamSubscriptions(publisherName);

            log.info(
                    "ADDED listener for Subject[{}] listener[{}] DispatcherType:{}",
                    aSubject,
                    listener,
                    conflate ? "conflate" : "sequential"
            );

        }
    }

    private Disposable establishFlux(SubscriberConnectionInfo subscriberConnectionInfo,
                                     SubscriptionInfo subscriptionInfo,
                                     RSocketRequester requester, Object attempt) {

        RSocketRequester.RequestSpec requestSpec =
                requester.route(subscriberConnectionInfo.getName());

        Flux<PublisherPayload> flux = requestSpec
                .data(subscriptionInfo.originalSubscriptionRequest)
                .retrieveFlux(PublisherPayload.class)
                // Safety: if publisher is faster than consumer, only keep the latest message
                // (use onBackpressureBuffer(...) if you must not drop anything)
                .onBackpressureLatest()
                // Prevent Reactor from flooding publisher with unbounded demand
                .limitRate(256)
                // Offload heavy work from Netty I/O threads
                //.publishOn(Schedulers.boundedElastic());
                .publishOn(Schedulers.parallel());

        Disposable disposable = flux.subscribe(
                messagePayload -> {
                    // Process message safely on your executor threads
                    dispatchMessage(subscriberConnectionInfo, subscriptionInfo.subject, messagePayload);
                },
                error -> {
                    handleFluxError(
                            subscriberConnectionInfo,
                            subscriptionInfo,
                            attempt,
                            error
                    );
                },
                () -> {
                    displayCompletion(
                            subscriberConnectionInfo.getName(),
                            subscriptionInfo.originalSubscriptionRequest
                    );
                }
        );

        return disposable;
    }

    private void displayCompletion(String publisherName, SubscriptionRequest subscriptionRequest) {
        log.info("{} subject[{}] has closed gracefully", publisherName, subscriptionRequest.getSubject());
    }

    private void handleFluxError(SubscriberConnectionInfo sci, SubscriptionInfo subscriptionInfo,
                                 Object attempt, Throwable error) {
        log.info("{} subject[{}] ERROR : {}", sci.getName(), subscriptionInfo.subject, error);

        if (error instanceof ClosedChannelException
                || error.getClass().getName().equals("reactor.core.Exceptions$RetryExhaustedException")) {

            // Never acquire lifecycleLock on a Reactor callback thread. The same timer executes
            // notifications after a running recovery finishes, including immediate replacement errors.
            try {
                sci.reSubscribeTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        synchronized (lifecycleLock) {
                            if (subscriberInfoMap.get(sci.getName()) == sci
                                    && clientSubscriptions.get(sci.getName() + ":" + subscriptionInfo.subject)
                                    == subscriptionInfo
                                    && subscriptionInfo.streamAttempt == attempt) {
                                scheduleRecovery(sci);
                            } else {
                                log.debug("Ignoring obsolete stream error for {} {}",
                                        sci.getName(), subscriptionInfo.subject);
                            }
                        }
                    }
                }, 0);
            } catch (RuntimeException ex) {
                log.warn("Failed queueing recovery notification for publisher {}", sci.getName(), ex);
            }
        }
    }

    /**
     * Remove the local logical subscription for the listener.
     * Then recompute the minimal upstream subscription set.
     */
    public <T> void unsubscribe(String publisherName, String aSubject, MessageReceiverInterface<T> listener) {
        synchronized (lifecycleLock) {
            SubscriberConnectionInfo subscriberConnectionInfo = subscriberInfoMap.get(publisherName);
            if (subscriberConnectionInfo == null) {
                log.error("{} is not a valid publisher to send unsubscribe {} to", publisherName, aSubject);
                return;
            }

            boolean wildcard = aSubject.endsWith("*");

            LocalSubscription localSubscription =
                    wildcard
                            ? subscriberConnectionInfo.wildcardListeners.get(aSubject.replace("*", ""))
                            : subscriberConnectionInfo.subjectListeners.get(aSubject);

            if (localSubscription == null) {
                log.info("{} subject[{}] does not exist as a current subscription : IGNORE", publisherName, aSubject);
                return;
            }

            // find listener
            MessageProcessorEntry messageProcessorEntry =
                    localSubscription.listeners.stream()
                            .filter(entry -> entry.messageReceiver == listener)
                            .findFirst()
                            .orElse(null);

            if (messageProcessorEntry == null) {
                log.info("{} subject[{}] listener not found : IGNORE", publisherName, aSubject);
                return;
            }

            // remove listener
            localSubscription.listeners.remove(messageProcessorEntry);

            log.info(
                    "REMOVED LISTENER:{} from {} subject[{}] remaining count={}",
                    listener.getClass().getSimpleName(),
                    publisherName,
                    aSubject,
                    localSubscription.listeners.size()
            );

            // if no listeners remain
            if (localSubscription.listeners.isEmpty()) {
                if (wildcard) {
                    subscriberConnectionInfo.wildcardListeners.remove(aSubject.replace("*", ""));
                } else {
                    subscriberConnectionInfo.subjectListeners.remove(aSubject);
                }
            }

            recomputeUpstreamSubscriptions(publisherName);

        }
    }

    private <T> void dispatchMessage(
            SubscriberConnectionInfo subscriberConnectionInfo,
            String originalSubject,
            T messagePayload
    ) {

        PublisherPayload publisherPayload = (PublisherPayload) messagePayload;
        String subject = publisherPayload.getSubject();

        Set<MessageReceiverInterface<?>> alreadyNotified =
                Collections.newSetFromMap(new IdentityHashMap<>());

        // ----- exact match -----
        LocalSubscription exactSubscription =
                subscriberConnectionInfo.subjectListeners.get(subject);

        if (exactSubscription != null) {
            for (MessageProcessorEntry entry : exactSubscription.listeners) {
                if (alreadyNotified.add(entry.messageReceiver)) {
                    entry.queueExecutor.process(publisherPayload, entry);
                }
            }
        }

        // ----- wildcard matches -----
        if (!subscriberConnectionInfo.wildcardListeners.isEmpty()) {
            for (Map.Entry<String, LocalSubscription> wildcardEntry :
                    subscriberConnectionInfo.wildcardListeners.entrySet()) {

                String prefix = wildcardEntry.getKey();

                if (subject.length() > prefix.length() && subject.startsWith(prefix)) {
                    LocalSubscription wildcardSubscription = wildcardEntry.getValue();

                    for (MessageProcessorEntry entry : wildcardSubscription.listeners) {
                        if (alreadyNotified.add(entry.messageReceiver)) {
                            entry.queueExecutor.process(publisherPayload, entry);
                        }
                    }
                }
            }
        }
    }
}
