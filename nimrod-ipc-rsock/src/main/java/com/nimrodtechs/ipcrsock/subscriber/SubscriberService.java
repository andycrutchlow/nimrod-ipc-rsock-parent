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

    static class SubscriberConnectionInfo {
        private String name;
        private String host;
        private int port;

        Map<String,List<MessageProcessorEntry>> subjectListeners = new HashMap<>();
        Map<String,List<MessageProcessorEntry>> wildcardListeners = new HashMap<>();

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

    class ReSubscribeTask extends TimerTask {
        private SubscriberConnectionInfo subscriberConnectionInfo;
        private Map<String, SubscriptionInfo> clientSubscriptions;

        public ReSubscribeTask(SubscriberConnectionInfo subscriberConnectionInfo,Map<String, SubscriptionInfo> clientSubscriptions) {
            this.subscriberConnectionInfo = subscriberConnectionInfo;
            this.clientSubscriptions = clientSubscriptions;
        }

        @Override
        public void run() {
            StringBuffer sb = new StringBuffer();
            subscriberConnectionInfo.subjectListeners.keySet().forEach(subscription -> {
                if(sb.length()>0) {
                    sb.append(",");
                }
                sb.append(subscription);
            });
            subscriberConnectionInfo.wildcardListeners.keySet().forEach(subscription -> {
                if(sb.length()>0) {
                    sb.append(",");
                }
                sb.append(subscription+"*");
            });
            log.info("Try to resubscribe on publisher ["+ subscriberConnectionInfo.getName()+"] subscriptions["+sb.toString()+"]");
            if(subscriberConnectionInfo.getRSocketRequester() != null) {
                subscriberConnectionInfo.getRSocketRequester().dispose();
            }
            subscriberConnectionInfo.setrSocketRequester(getRSocketRequester(subscriberConnectionInfo));
            for(String key : subscriberConnectionInfo.subjectListeners.keySet()) {
                String fullKey = subscriberConnectionInfo.getName()+":"+key;
                SubscriptionInfo subscriptionInfo = clientSubscriptions.get(fullKey);
                if(subscriptionInfo != null) {
                    if(subscriptionInfo.getDisposable().isDisposed() == false) {
                        subscriptionInfo.getDisposable().dispose();
                    }
                    Disposable disposable = establishFlux(subscriberConnectionInfo,subscriptionInfo);
                    subscriptionInfo.setDisposable(disposable);
                }
            }
            for(String key : subscriberConnectionInfo.wildcardListeners.keySet()) {
                String fullKey = subscriberConnectionInfo.getName()+":"+key;
                SubscriptionInfo subscriptionInfo = clientSubscriptions.get(fullKey+"*");
                if(subscriptionInfo != null) {
                    if(subscriptionInfo.getDisposable().isDisposed() == false) {
                        subscriptionInfo.getDisposable().dispose();
                    }
                    Disposable disposable = establishFlux(subscriberConnectionInfo,subscriptionInfo);
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

    public <T> void subscribe(String publisherName, String aSubject, MessageReceiverInterface<T> listener, Class<T> payloadClass, boolean conflate) throws NimrodPubSubException {
        SubscriberConnectionInfo subscriberConnectionInfo = subscriberInfoMap.get(publisherName);
        if(subscriberConnectionInfo == null) {
            throw new NimrodPubSubException(publisherName+" is not a valid publisher to send subscribe "+aSubject+" to");
        }
        List<MessageProcessorEntry> messageProcessorEntries;
        boolean wildcard = aSubject.endsWith("*");
        if(wildcard) {
            messageProcessorEntries = subscriberConnectionInfo.wildcardListeners.get(aSubject.replace("*",""));
            //Need to also check if there is an existing wildcard listener that would already cover this new subscription and is for the same listener..
            //E.G. aaa.* already is present and now subscribing for aaa.bbb.*
            boolean existingWildcardCovered = subscriberConnectionInfo.wildcardListeners.entrySet()
                    .stream()
                    .anyMatch(entry -> aSubject.startsWith(entry.getKey()) && entry.getValue().stream().anyMatch(e -> e.messageReceiver == listener));
            if (existingWildcardCovered) {
                log.info("{} wildcard subject[{}] listener [{}] is already present under a broader wildcard : IGNORE", publisherName, aSubject, listener.toString());
                return;
            }
            //Need to check if adding a new subscription with a less granular wildcard that would now cover one or more existing more granular entries that are now redundant and need to be removed but only in the case where the listener is the same. 
            List<String> redundantKeys = subscriberConnectionInfo.wildcardListeners.entrySet()
                    .stream()
                    .filter(entry -> entry.getKey().startsWith(aSubject.replace("*", ""))
                            && entry.getValue().stream().anyMatch(e -> e.messageReceiver == listener))
                    .map(Map.Entry::getKey)
                    .toList();
            redundantKeys.forEach(redundantKey -> {
                log.info("{} wildcard subject [{}] listener [{}] is now redundant due to new broader wildcard : REMOVE",
                        publisherName, redundantKey + "*", listener.toString());
                unsubscribe(publisherName, redundantKey + "*", listener);
            });

        } else {
            messageProcessorEntries = subscriberConnectionInfo.subjectListeners.get(aSubject);
        }

        //TODO Check if this listener already exists
        if(messageProcessorEntries == null) {
            messageProcessorEntries = new ArrayList<>();
            if(wildcard) {
                subscriberConnectionInfo.wildcardListeners.put(aSubject.replace("*",""), messageProcessorEntries);
            } else {
                subscriberConnectionInfo.subjectListeners.put(aSubject, messageProcessorEntries);
            }
        }
        if(messageProcessorEntries.stream().anyMatch(entry -> entry.messageReceiver == listener)) {
            log.info(publisherName+" subject["+aSubject+"] listener "+listener.toString()+" already present : IGNORE");
            return;
        }
        //If this is NOT the first subscription to this subject then check the QueueExecutor is the same type - it must be! otherwise error
        if(messageProcessorEntries.size() == 1) {
            if( !((messageProcessorEntries.get(0).queueExecutor instanceof ConflatingExecutor && conflate) || (messageProcessorEntries.get(0).queueExecutor instanceof SequentialExecutor && conflate==false))){
                log.info(publisherName+" subject["+aSubject+"] adding another listener BUT cannot have different type of QueueExecutor : IGNORE");
                return;
            }
        }
        MessageProcessorEntry messageProcessorEntry = new MessageProcessorEntry(listener,conflate ? subscriberConnectionInfo.conflatingQueueExecutor: subscriberConnectionInfo.sequentialQueueExecutor);
        messageProcessorEntries.add(messageProcessorEntry);
        if(messageProcessorEntries.size() == 1) {
            SubscriptionRequest subscriptionRequest = new SubscriptionRequest(SubscriptionDirective.REQUEST, subscriberProcessName, aSubject, wildcard);
            SubscriptionInfo<T> subscriptionInfo = new SubscriptionInfo(aSubject, payloadClass, subscriptionRequest);
            subscriptionInfo.setDisposable(establishFlux(subscriberConnectionInfo, subscriptionInfo));
            clientSubscriptions.put(publisherName + ":" + aSubject, subscriptionInfo);
            log.info("SUBSCRIBED TO:" + publisherName + " " + " subject[" + subscriptionRequest.getSubject() + "] listener["+listener.toString()+"] DispatcherType:" + (conflate ? "conflate" : "sequential"));
        } else {
            log.info("ADDED another listener for existing Subject["+ aSubject+"] listener["+listener.toString()+"] DispatcherType:" + (conflate ? "conflate" : "sequential"));
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
     * There show be a confirmation message back from the publisher telling us that DirectProcessor has be closed gracefully *
     * @param publisherName
     * @param aSubject
     * @param listener
     * @param <T>
     */
    public <T> void unsubscribe(String publisherName, String aSubject, MessageReceiverInterface<T> listener)  {
        SubscriberConnectionInfo subscriberConnectionInfo = subscriberInfoMap.get(publisherName);
        if(subscriberConnectionInfo == null) {
            log.error(publisherName+" is not a valid publisher to send unsubscribe "+aSubject+" to");
        }
        List<MessageProcessorEntry> messageProcessorEntries;
        if(aSubject.endsWith("*")) {
            messageProcessorEntries = subscriberConnectionInfo.wildcardListeners.get(aSubject.replace("*",""));
        } else {
            messageProcessorEntries = subscriberConnectionInfo.subjectListeners.get(aSubject);
        }
        //TODO Check if this listener already exists
        if(messageProcessorEntries == null) {
            log.info(publisherName+" subject["+aSubject+"] does not exist as a current subscription : IGNORE");
            return;
        }
        //Look for the listener in the current list of listeners and remove it
        MessageProcessorEntry messageProcessorEntry = messageProcessorEntries.stream()
                .filter(entry -> entry.messageReceiver == listener)
                .findFirst()
                .orElse(null);

        if(messageProcessorEntry != null) {
            //messageDispatcher.queueExecutor.dispose();
            messageProcessorEntries.remove(messageProcessorEntry);
            log.info("REMOVED LISTENER:"+listener.getClass().getSimpleName()+" from "+publisherName+" "+" subject["+aSubject+"] remaining count="+ messageProcessorEntries.size());
        } else {
            log.info(publisherName+" subject["+aSubject+"] does not exist as a current subscription : IGNORE");
            return;
        }
        if(messageProcessorEntries.size() == 0) {
            Disposable disposable = clientSubscriptions.get(publisherName + ":" + aSubject).disposable;
            if(disposable != null) {
                disposable.dispose();
            }
            //Tell the publisher to stop publishing messages to this process for this subject
            RSocketRequester.RequestSpec requestSpec = subscriberConnectionInfo.getRSocketRequester().route(publisherName);
            requestSpec.data(new SubscriptionRequest(SubscriptionDirective.CANCEL,subscriberProcessName,aSubject)).send().subscribe();
            //TODO TEst this !!!!
            if(aSubject.endsWith("*")) {
                subscriberConnectionInfo.wildcardListeners.remove(aSubject.replace("*",""));
            } else {
                subscriberConnectionInfo.subjectListeners.remove(aSubject);
            }
            log.info("UNSUBSCRIBE FROM:"+publisherName+" "+" subject["+aSubject+"]");
        }
    }

    private <T> void dispatchMessage(SubscriberConnectionInfo subscriberConnectionInfo, String originalSubject, T messagePayload) {
        //Look for exact match on originalSubject
        PublisherPayload publisherPayload = (PublisherPayload)messagePayload;
        HashSet<MessageProcessorEntry> listenersToBeNotified = new HashSet<>();
        List<MessageProcessorEntry> messageProcessorEntries = subscriberConnectionInfo.subjectListeners.get(publisherPayload.getSubject());
        if(messageProcessorEntries != null) {
            for (MessageProcessorEntry messageProcessorEntry : messageProcessorEntries) {
                listenersToBeNotified.add(messageProcessorEntry);
            }
        }
        //Look for any wildcard matches...the subscription logic above should ensure listener will only be called once..even if it duplicated a subject or called with a more granular version of wildcard subscription in subject..e.g. aaa.bbb.* and aaa.*
        if(subscriberConnectionInfo.wildcardListeners.size() > 0) {
            for (Iterator<Map.Entry<String, List<MessageProcessorEntry>>> it = subscriberConnectionInfo.wildcardListeners.entrySet().iterator(); it.hasNext(); ) {
                //PublisherSocketImpl.SubscriberFluxInfo sfi = it.next().getValue();
                Map.Entry<String, List<MessageProcessorEntry>> next = it.next();
                if(publisherPayload.getSubject().length() > next.getKey().length() && publisherPayload.getSubject().startsWith(next.getKey()) ) {
                    for(MessageProcessorEntry messageProcessorEntry : next.getValue()) {
                        listenersToBeNotified.add(messageProcessorEntry);
                    }
                }
            }
        }
        //At this point hand off the calls to the various listeners for this message to an executor queue.
        listenersToBeNotified.stream().forEach( entry -> entry.queueExecutor.process(publisherPayload,entry));

        //listenersToBeNotified.stream().forEach( entry -> entry.messageReceiver.messageReceived(subscriberInfo.getName(),publisherPayload.getSubject(),publisherPayload.getPayload()));
    }
}