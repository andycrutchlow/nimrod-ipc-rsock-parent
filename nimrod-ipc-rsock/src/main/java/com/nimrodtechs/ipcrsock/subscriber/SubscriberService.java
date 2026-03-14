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

    private Map<String, SubscriberConnectionInfo> subscriberInfoMap = new HashMap<>();

    Map<String, SubscriptionInfo> clientSubscriptions = new HashMap<>();

    static class SubscriptionInfo<T>  {
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
        final List<MessageProcessorEntry> listeners = new ArrayList<>();

        boolean upstreamActive = false;
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

        Map<String, LocalSubscription> subjectListeners = new HashMap<>();
        Map<String, LocalSubscription> wildcardListeners = new HashMap<>();

        QueueExecutor conflatingQueueExecutor;
        QueueExecutor sequentialQueueExecutor;

        private RSocketRequester rSocketRequester;

        public SubscriberConnectionInfo(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
            conflatingQueueExecutor = new ConflatingExecutor(name);
            sequentialQueueExecutor = new SequentialExecutor(name);
            reSubscribeTimer = new Timer(name+"-reconnect-task",true);
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

        Timer reSubscribeTimer;

        TimerTask reSubscribeTimerTask;

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
    private <T> void activateUpstream(
            String publisherName,
            SubscriberConnectionInfo subscriberConnectionInfo,
            LocalSubscription localSubscription,
            Class<T> payloadClass
    ) {
        if (localSubscription.upstreamActive) {
            return;
        }

        SubscriptionRequest subscriptionRequest =
                new SubscriptionRequest(
                        SubscriptionDirective.REQUEST,
                        subscriberProcessName,
                        localSubscription.subject,
                        localSubscription.wildcard
                );

        SubscriptionInfo<T> subscriptionInfo =
                new SubscriptionInfo<>(localSubscription.subject, payloadClass, subscriptionRequest);

        subscriptionInfo.setDisposable(establishFlux(subscriberConnectionInfo, subscriptionInfo));
        clientSubscriptions.put(publisherName + ":" + localSubscription.subject, subscriptionInfo);

        localSubscription.upstreamActive = true;
        localSubscription.payloadClass = payloadClass;

        //log.info("SUBSCRIBED TO:{} subject[{}]", publisherName, localSubscription.subject);
    }

    private void deactivateUpstream(
            String publisherName,
            SubscriberConnectionInfo subscriberConnectionInfo,
            LocalSubscription localSubscription
    ) {
        if (!localSubscription.upstreamActive) {
            return;
        }

        SubscriptionInfo subscriptionInfo =
                clientSubscriptions.remove(publisherName + ":" + localSubscription.subject);

        if (subscriptionInfo != null && subscriptionInfo.getDisposable() != null) {
            subscriptionInfo.getDisposable().dispose();
        }

        RSocketRequester.RequestSpec requestSpec =
                subscriberConnectionInfo.getRSocketRequester().route(publisherName);

        requestSpec.data(
                        new SubscriptionRequest(
                                SubscriptionDirective.CANCEL,
                                subscriberProcessName,
                                localSubscription.subject
                        )
                ).send()
                .doOnError(e -> log.error("Failed sending CANCEL for {} {}", publisherName, localSubscription.subject, e))
                .subscribe();

        localSubscription.upstreamActive = false;

        log.info("UNSUBSCRIBE FROM:{} subject[{}]", publisherName, localSubscription.subject);
    }

    private boolean wildcardCovers(String wildcardSubject, String subject) {
        String prefix = wildcardSubject.replace("*", "");
        return subject.length() > prefix.length() && subject.startsWith(prefix);
    }

    private boolean isCoveredByBroaderWildcard(
            SubscriberConnectionInfo subscriberConnectionInfo,
            String subject,
            MessageReceiverInterface<?> listener
    ) {
        return subscriberConnectionInfo.wildcardListeners.values().stream()
                .filter(ls -> ls.upstreamActive)
                .anyMatch(ls ->
                        wildcardCovers(ls.subject, subject) &&
                                ls.listeners.stream().anyMatch(entry -> entry.messageReceiver == listener)
                );
    }

    class ReSubscribeTask extends TimerTask {

        private SubscriberConnectionInfo subscriberConnectionInfo;
        private Map<String, SubscriptionInfo> clientSubscriptions;

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

            subscriberConnectionInfo.subjectListeners.values().forEach(ls -> {
                if (ls.upstreamActive) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(ls.subject);
                }
            });

            subscriberConnectionInfo.wildcardListeners.values().forEach(ls -> {
                if (ls.upstreamActive) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(ls.subject);
                }
            });

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

            // ----- exact subjects -----
            for (LocalSubscription localSubscription :
                    subscriberConnectionInfo.subjectListeners.values()) {

                if (!localSubscription.upstreamActive) {
                    continue;
                }

                String fullKey =
                        subscriberConnectionInfo.getName() + ":" + localSubscription.subject;

                SubscriptionInfo subscriptionInfo = clientSubscriptions.get(fullKey);

                if (subscriptionInfo != null) {

                    if (!subscriptionInfo.getDisposable().isDisposed()) {
                        subscriptionInfo.getDisposable().dispose();
                    }

                    Disposable disposable =
                            establishFlux(subscriberConnectionInfo, subscriptionInfo);

                    subscriptionInfo.setDisposable(disposable);
                }
            }

            // ----- wildcards -----
            for (LocalSubscription localSubscription :
                    subscriberConnectionInfo.wildcardListeners.values()) {

                if (!localSubscription.upstreamActive) {
                    continue;
                }

                String fullKey =
                        subscriberConnectionInfo.getName() + ":" + localSubscription.subject;

                SubscriptionInfo subscriptionInfo = clientSubscriptions.get(fullKey);

                if (subscriptionInfo != null) {

                    if (!subscriptionInfo.getDisposable().isDisposed()) {
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
        if(subscriberProperties.getSetup() == null) {
            //Quietly return and don't attempt to set up subscriber connections or assume that subscriber connections will be set up programatically
            return;
        }
        if(subscriberProcessNameOverride != null) {
            subscriberProcessName = subscriberProcessNameOverride;
        }
        if(subscriberProcessName == null) {
            log.error("If subscriberProperties are provided then property or VM param spring.application.name must be supplied");
            throw new NimrodPubSubException("If subscriberProperties are provided then property or VM param spring.application.name must be supplied");
        }
        for (String subscriberInfoItems : subscriberProperties.getSetup()) {
            String[] items = subscriberInfoItems.split(",");
            addSubscriberSocket(subscriberProcessName,items[0], items[1], Integer.valueOf(items[2]));
        }
    }

    @PreDestroy
    void destroy() {
        log.info("Shutdown : subscriberInfoMap size="+subscriberInfoMap.size());
        //subscriberInfoMap.entrySet().stream().forEach( entry -> entry.queueExecutor.process(publisherPayload,entry));

    }

    public void addSubscriberSocket(String subscriberNameOnDemand, String name, String host, int port) throws NimrodPubSubException {
        SubscriberConnectionInfo subscriberConnectionInfo = new SubscriberConnectionInfo(name,host,port);
        if(subscriberProcessName == null) {
            subscriberProcessName = subscriberNameOnDemand;
        } else {
            if(subscriberProcessName.equals(subscriberNameOnDemand) == false) {
                //That's a problem !!!
                throw new NimrodPubSubException("You cannot change subscriberProcessName["+subscriberProcessName+"] to ["+subscriberNameOnDemand+"]");
            }
        }
        subscriberConnectionInfo.setrSocketRequester(getRSocketRequester(subscriberConnectionInfo));
        subscriberInfoMap.put(subscriberConnectionInfo.getName(), subscriberConnectionInfo);
    }

    private RSocketRequester getRSocketRequester(SubscriberConnectionInfo subscriberConnectionInfo) {

        RSocketRequester.Builder builder = RSocketRequester.builder();
        //TODO make keepAlive settings parameters ... need long max duration when debugging
        RSocketRequester rSocketRequester =  builder
                .rsocketConnector(
                        rSocketConnector -> {
                            rSocketConnector.keepAlive(Duration.ofSeconds(90),Duration.ofSeconds(7200));
                            rSocketConnector.reconnect(
                                    Retry.fixedDelay(Long.MAX_VALUE, Duration.ofSeconds(2))
                            );
                        })
                .rsocketStrategies(rSocketStrategies)
                .dataMimeType(new MimeType("application", "x-kryo"))
                .tcp(subscriberConnectionInfo.getHost(), subscriberConnectionInfo.getPort());
        log.info("Configured Nimrod RSocket Subscriber for "+subscriberConnectionInfo.getName()+" on host "+
                subscriberConnectionInfo.getHost()+" port "+subscriberConnectionInfo.getPort());
        return rSocketRequester;
    }
    private static final Pattern SUBJECT_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)*(\\.\\*)?$");

    private void validateSubject(String subject) throws NimrodPubSubException {
        if (!SUBJECT_PATTERN.matcher(subject).matches()) {
            throw new NimrodPubSubException(
                    "Invalid subject pattern: " + subject +
                            ". Valid examples: aaa, aaa.bbb, aaa.*, aaa.bbb.*"
            );
        }
    }

    public <T> void subscribe(
            String publisherName,
            String aSubject,
            MessageReceiverInterface<T> listener,
            Class<T> payloadClass,
            boolean conflate
    ) throws NimrodPubSubException {

        // validate publisherName
        SubscriberConnectionInfo subscriberConnectionInfo = subscriberInfoMap.get(publisherName);
        if (subscriberConnectionInfo == null) {
            throw new NimrodPubSubException(
                    publisherName + " is not a valid publisher to send subscribe " + aSubject + " to"
            );
        }

        // validate subject
        validateSubject(aSubject);

        boolean wildcard = aSubject.endsWith("*");

        LocalSubscription localSubscription =
                getOrCreateLocalSubscription(subscriberConnectionInfo, aSubject, wildcard);

        // If this wildcard is already covered by an existing broader wildcard for the same listener, ignore it.
        // Example: aaa.* already exists, now trying to subscribe aaa.bbb.*
        if (wildcard) {
            boolean existingWildcardCovered =
                    subscriberConnectionInfo.wildcardListeners.values().stream()
                            .anyMatch(existing ->
                                    !existing.subject.equals(aSubject)
                                            && existing.upstreamActive
                                            && wildcardCovers(existing.subject, aSubject.replace("*", "x"))
                                            && existing.listeners.stream().anyMatch(e -> e.messageReceiver == listener)
                            );

            if (existingWildcardCovered) {
                log.info(
                        "{} wildcard subject[{}] listener [{}] is already present under a broader wildcard : IGNORE",
                        publisherName, aSubject, listener
                );
                return;
            }
        }

        // duplicate listener check
        if (localSubscription.listeners.stream().anyMatch(entry -> entry.messageReceiver == listener)) {
            log.info(
                    "{} subject[{}] listener {} already present : IGNORE",
                    publisherName, aSubject, listener
            );
            return;
        }

        // if this is NOT the first listener for this subject, queue executor type must match
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

        // add listener locally
        MessageProcessorEntry messageProcessorEntry =
                new MessageProcessorEntry(
                        listener,
                        conflate ? subscriberConnectionInfo.conflatingQueueExecutor
                                : subscriberConnectionInfo.sequentialQueueExecutor
                );

        localSubscription.listeners.add(messageProcessorEntry);
        localSubscription.payloadClass = payloadClass;

        // If this local subscription is not yet active upstream, decide whether it should be.
        if (!localSubscription.upstreamActive) {
            boolean coveredByBroaderWildcard =
                    !wildcard && isCoveredByBroaderWildcard(subscriberConnectionInfo, aSubject, listener);

            if (!coveredByBroaderWildcard) {
                activateUpstream(publisherName, subscriberConnectionInfo, localSubscription, payloadClass);

                log.info(
                        "SUBSCRIBED TO:{} subject[{}] listener[{}] DispatcherType:{}",
                        publisherName,
                        aSubject,
                        listener,
                        conflate ? "conflate" : "sequential"
                );
            } else {
                log.info(
                        "ADDED listener locally for covered subject[{}] listener[{}] DispatcherType:{}",
                        aSubject,
                        listener,
                        conflate ? "conflate" : "sequential"
                );
            }
        } else {
            log.info(
                    "ADDED another listener for existing Subject[{}] listener[{}] DispatcherType:{}",
                    aSubject,
                    listener,
                    conflate ? "conflate" : "sequential"
            );
        }

        // If this is a wildcard subscription, it may now cover narrower exact subjects / wildcards
        // for the same listener. Those should remain locally, but be deactivated upstream.
        if (wildcard) {

            // Deactivate exact subjects now covered by this wildcard
            subscriberConnectionInfo.subjectListeners.values().stream()
                    .filter(ls -> !ls.subject.equals(aSubject))
                    .filter(ls -> wildcardCovers(aSubject, ls.subject))
                    .filter(ls -> ls.upstreamActive)
                    .filter(ls -> ls.listeners.stream().anyMatch(e -> e.messageReceiver == listener))
                    .forEach(ls -> {
                        log.info(
                                "{} subject [{}] listener [{}] is now covered by wildcard [{}] : DEACTIVATE UPSTREAM",
                                publisherName, ls.subject, listener, aSubject
                        );
                        deactivateUpstream(publisherName, subscriberConnectionInfo, ls);
                    });

            // Deactivate narrower wildcards now covered by this broader wildcard
            subscriberConnectionInfo.wildcardListeners.values().stream()
                    .filter(ls -> !ls.subject.equals(aSubject))
                    .filter(ls -> wildcardCovers(aSubject, ls.subject.replace("*", "x")))
                    .filter(ls -> ls.upstreamActive)
                    .filter(ls -> ls.listeners.stream().anyMatch(e -> e.messageReceiver == listener))
                    .forEach(ls -> {
                        log.info(
                                "{} wildcard subject [{}] listener [{}] is now covered by wildcard [{}] : DEACTIVATE UPSTREAM",
                                publisherName, ls.subject, listener, aSubject
                        );
                        deactivateUpstream(publisherName, subscriberConnectionInfo, ls);
                    });
        }
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
                .publishOn(Schedulers.boundedElastic());

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
        log.info(publisherName+" "+" subject["+subscriptionRequest.getSubject()+"] has closed gracefully");
    }

    private void handleFluxError(String publisherName, SubscriptionRequest subscriptionRequest,Object error) {
        log.info(publisherName+" "+" subject["+subscriptionRequest.getSubject()+"] ERROR : "+error);
        if(error instanceof ClosedChannelException|| error.getClass().getName().equals("reactor.core.Exceptions$RetryExhaustedException")) {
            SubscriberConnectionInfo subscriberConnectionInfo = subscriberInfoMap.get(publisherName);
            if(subscriberConnectionInfo == null) {
                return;
            }
            //Start a timer task that loops trying to re-establish a good connection to the publisher and re-subscribe to any existing subscriptions found
            if(subscriberConnectionInfo.subjectListeners.size() > 0 || subscriberConnectionInfo.wildcardListeners.size() > 0) {
                if(subscriberConnectionInfo.reSubscribeTimerTask == null) {
                    subscriberConnectionInfo.reSubscribeTimerTask = new ReSubscribeTask(subscriberConnectionInfo,clientSubscriptions);
                    subscriberConnectionInfo.reSubscribeTimer.schedule(subscriberConnectionInfo.reSubscribeTimerTask,2000);
                }
            }
        }
    }

    /**
     * Remove the subscription for the listener.
     * If there are now no more listeners in this process for this subject then use fire-and-forget send to tell the publisher to stop publishing.
     * There should be a confirmation message back from the publisher telling us that DirectProcessor has be closed gracefully *
     * @param publisherName
     * @param aSubject
     * @param listener
     * @param <T>
     */
    public <T> void unsubscribe(String publisherName, String aSubject, MessageReceiverInterface<T> listener) {

        SubscriberConnectionInfo subscriberConnectionInfo = subscriberInfoMap.get(publisherName);
        if (subscriberConnectionInfo == null) {
            log.error("{} is not a valid publisher to send unsubscribe {} to", publisherName, aSubject);
            return;
        }

        boolean wildcard = aSubject.endsWith("*");

        LocalSubscription localSubscription;

        if (wildcard) {
            localSubscription = subscriberConnectionInfo.wildcardListeners.get(aSubject.replace("*", ""));
        } else {
            localSubscription = subscriberConnectionInfo.subjectListeners.get(aSubject);
        }

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

            if (localSubscription.upstreamActive) {
                deactivateUpstream(publisherName, subscriberConnectionInfo, localSubscription);
            }

            if (wildcard) {
                subscriberConnectionInfo.wildcardListeners.remove(aSubject.replace("*", ""));
            } else {
                subscriberConnectionInfo.subjectListeners.remove(aSubject);
            }

            log.info("UNSUBSCRIBE FROM:{} subject[{}]", publisherName, aSubject);

            // ----------------------------------------------------
            // NEW LOGIC : restore exact subjects previously covered
            // ----------------------------------------------------

            if (wildcard) {

                String wildcardPrefix = aSubject.replace("*", "");

                for (LocalSubscription subjectSub : subscriberConnectionInfo.subjectListeners.values()) {

                    // subject must match wildcard
                    if (!subjectSub.subject.startsWith(wildcardPrefix)) {
                        continue;
                    }

                    if (subjectSub.listeners.isEmpty()) {
                        continue;
                    }

                    if (subjectSub.upstreamActive) {
                        continue;
                    }

                    // check if another wildcard still covers it
                    boolean stillCovered =
                            subscriberConnectionInfo.wildcardListeners.values().stream()
                                    .filter(w -> w.upstreamActive)
                                    .anyMatch(w -> wildcardCovers(w.subject, subjectSub.subject));

                    if (!stillCovered) {

                        log.info(
                                "{} restoring upstream subscription for subject [{}] after wildcard removal",
                                publisherName,
                                subjectSub.subject
                        );

                        @SuppressWarnings("unchecked")
                        Class<Object> payloadClazz = (Class<Object>) subjectSub.payloadClass;

                        activateUpstream(
                                publisherName,
                                subscriberConnectionInfo,
                                subjectSub,
                                payloadClazz
                        );
                    }
                }
            }
        }
    }

    private <T> void dispatchMessage(
            SubscriberConnectionInfo subscriberConnectionInfo,
            String originalSubject,
            T messagePayload
    ) {

        PublisherPayload publisherPayload = (PublisherPayload) messagePayload;
        String subject = publisherPayload.getSubject();

        // ----- exact match -----

        LocalSubscription exactSubscription =
                subscriberConnectionInfo.subjectListeners.get(subject);

        if (exactSubscription != null) {

            for (MessageProcessorEntry entry : exactSubscription.listeners) {
                entry.queueExecutor.process(publisherPayload, entry);
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

                        // skip duplicate listener already called by exact subscription
                        if (exactSubscription != null &&
                                exactSubscription.listeners.contains(entry)) {
                            continue;
                        }

                        entry.queueExecutor.process(publisherPayload, entry);
                    }
                }
            }
        }
    }
}