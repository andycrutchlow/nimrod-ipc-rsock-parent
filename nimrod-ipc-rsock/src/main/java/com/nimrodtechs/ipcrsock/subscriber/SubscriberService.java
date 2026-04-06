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

    private final Map<String, SubscriberConnectionInfo> subscriberInfoMap = new HashMap<>();

    Map<String, SubscriptionInfo> clientSubscriptions = new HashMap<>();

    static class SubscriptionInfo<T> {
        String subject;
        Class<T> payloadClass;
        SubscriptionRequest originalSubscriptionRequest;
        Disposable disposable;

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

        subscriptionInfo.setDisposable(establishFlux(subscriberConnectionInfo, subscriptionInfo));
        clientSubscriptions.put(publisherName + ":" + subject, subscriptionInfo);

        subscriberConnectionInfo.upstreamActiveSubjects.add(subject);

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

        if (subscriptionInfo != null && subscriptionInfo.getDisposable() != null) {
            subscriptionInfo.getDisposable().dispose();
        }

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
                .doOnError(e -> log.error("Failed sending CANCEL for {} {}", publisherName, subject, e))
                .subscribe();

        subscriberConnectionInfo.upstreamActiveSubjects.remove(subject);

        log.info("UNSUBSCRIBE FROM:{} subject[{}]", publisherName, subject);
    }

    /**
     * Recompute the minimal upstream subscription set from the local logical subscriptions.
     *
     * Rules:
     * - Every wildcard local subscription with listeners is desired upstream.
     * - An exact subject local subscription is desired upstream only if it is NOT covered
     *   by any wildcard local subscription with listeners.
     */
    @SuppressWarnings("unchecked")
    private void recomputeUpstreamSubscriptions(String publisherName) {
        SubscriberConnectionInfo sci = subscriberInfoMap.get(publisherName);
        if (sci == null) {
            return;
        }

        Set<String> desired = new HashSet<>();

        // 1. All wildcards with listeners are desired upstream
        for (LocalSubscription sub : sci.wildcardListeners.values()) {
            if (!sub.listeners.isEmpty()) {
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
    }

    class ReSubscribeTask extends TimerTask {

        private final SubscriberConnectionInfo subscriberConnectionInfo;
        private final Map<String, SubscriptionInfo> clientSubscriptions;

        public ReSubscribeTask(
                SubscriberConnectionInfo subscriberConnectionInfo,
                Map<String, SubscriptionInfo> clientSubscriptions
        ) {
            this.subscriberConnectionInfo = subscriberConnectionInfo;
            this.clientSubscriptions = clientSubscriptions;
        }

        @Override
        public void run() {

            StringBuilder sb = new StringBuilder();

            for (String subject : subscriberConnectionInfo.upstreamActiveSubjects) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(subject);
            }

            log.info(
                    "Try to resubscribe on publisher [{}] subscriptions [{}]",
                    subscriberConnectionInfo.getName(),
                    sb.toString()
            );

            if (subscriberConnectionInfo.getRSocketRequester() != null) {
                subscriberConnectionInfo.getRSocketRequester().dispose();
            }

            subscriberConnectionInfo.setrSocketRequester(
                    getRSocketRequester(subscriberConnectionInfo)
            );

            for (String subject : subscriberConnectionInfo.upstreamActiveSubjects) {
                String fullKey = subscriberConnectionInfo.getName() + ":" + subject;
                SubscriptionInfo subscriptionInfo = clientSubscriptions.get(fullKey);

                if (subscriptionInfo != null) {
                    if (subscriptionInfo.getDisposable() != null && !subscriptionInfo.getDisposable().isDisposed()) {
                        subscriptionInfo.getDisposable().dispose();
                    }

                    Disposable disposable =
                            establishFlux(subscriberConnectionInfo, subscriptionInfo);

                    subscriptionInfo.setDisposable(disposable);
                }
            }

            subscriberConnectionInfo.reSubscribeTimerTask.cancel();
            subscriberConnectionInfo.reSubscribeTimerTask = null;
        }
    }

    @PostConstruct
    void init() throws NimrodPubSubException {
        if (subscriberProperties.getSetup() == null) {
            // Quietly return and don't attempt to set up subscriber connections or
            // assume that subscriber connections will be set up programmatically.
            return;
        }

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

        for (String subscriberInfoItems : subscriberProperties.getSetup()) {
            String[] items = subscriberInfoItems.split(",");
            addSubscriberSocket(subscriberProcessName, items[0], items[1], Integer.valueOf(items[2]));
        }
    }

    @PreDestroy
    void destroy() {
        log.info("Shutdown : subscriberInfoMap size={}", subscriberInfoMap.size());
    }

    public void addSubscriberSocket(String subscriberNameOnDemand, String name, String host, int port) throws NimrodPubSubException {
        SubscriberConnectionInfo subscriberConnectionInfo = new SubscriberConnectionInfo(name, host, port);

        if (subscriberProcessName == null) {
            subscriberProcessName = subscriberNameOnDemand;
        } else {
            if (!subscriberProcessName.equals(subscriberNameOnDemand)) {
                throw new NimrodPubSubException(
                        "You cannot change subscriberProcessName[" + subscriberProcessName + "] to [" + subscriberNameOnDemand + "]"
                );
            }
        }

        subscriberConnectionInfo.setrSocketRequester(getRSocketRequester(subscriberConnectionInfo));
        subscriberInfoMap.put(subscriberConnectionInfo.getName(), subscriberConnectionInfo);
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

    private Disposable establishFlux(SubscriberConnectionInfo subscriberConnectionInfo, SubscriptionInfo subscriptionInfo) {

        RSocketRequester.RequestSpec requestSpec =
                subscriberConnectionInfo.getRSocketRequester().route(subscriberConnectionInfo.getName());

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
                            subscriberConnectionInfo.getName(),
                            subscriptionInfo.originalSubscriptionRequest,
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

    private void handleFluxError(String publisherName, SubscriptionRequest subscriptionRequest, Object error) {
        log.info("{} subject[{}] ERROR : {}", publisherName, subscriptionRequest.getSubject(), error);

        if (error instanceof ClosedChannelException
                || error.getClass().getName().equals("reactor.core.Exceptions$RetryExhaustedException")) {

            SubscriberConnectionInfo subscriberConnectionInfo = subscriberInfoMap.get(publisherName);
            if (subscriberConnectionInfo == null) {
                return;
            }
            //Start a timer task that loops trying to re-establish a good connection to the publisher and re-subscribe to any existing subscriptions found
            if (!subscriberConnectionInfo.subjectListeners.isEmpty()
                    || !subscriberConnectionInfo.wildcardListeners.isEmpty()) {

                if (subscriberConnectionInfo.reSubscribeTimerTask == null) {
                    subscriberConnectionInfo.reSubscribeTimerTask =
                            new ReSubscribeTask(subscriberConnectionInfo, clientSubscriptions);
                    subscriberConnectionInfo.reSubscribeTimer.schedule(
                            subscriberConnectionInfo.reSubscribeTimerTask,
                            2000
                    );
                }
            }
        }
    }

    /**
     * Remove the local logical subscription for the listener.
     * Then recompute the minimal upstream subscription set.
     */
    public <T> void unsubscribe(String publisherName, String aSubject, MessageReceiverInterface<T> listener) {

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