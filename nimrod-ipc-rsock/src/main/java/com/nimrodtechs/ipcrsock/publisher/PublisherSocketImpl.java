package com.nimrodtechs.ipcrsock.publisher;

import com.nimrodtechs.ipcrsock.common.PublisherPayload;
import com.nimrodtechs.ipcrsock.common.SubscriptionDirective;
import com.nimrodtechs.ipcrsock.common.SubscriptionListener;
import com.nimrodtechs.ipcrsock.common.SubscriptionRequest;
import com.nimrodtechs.ipcrsock.serialization.KryoDecoder;
import com.nimrodtechs.ipcrsock.serialization.KryoEncoder;
import io.rsocket.ConnectionSetupPayload;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.SocketAcceptor;
import io.rsocket.core.RSocketServer;
import io.rsocket.transport.netty.server.TcpServerTransport;
import io.rsocket.util.DefaultPayload;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class PublisherSocketImpl implements SocketAcceptor {
    private static final Logger log = LoggerFactory.getLogger(PublisherSocketImpl.class);
    private final KryoEncoder kryoEncoder;
    private final KryoDecoder kryoDecoder;

    @Value("${nimrod.rsock.publisher.port:-1}")
    int publisherPort;

    private static int logLevel = 0;

    public PublisherSocketImpl(KryoEncoder kryoEncoder, KryoDecoder kryoDecoder) {
        this.kryoEncoder = kryoEncoder;
        this.kryoDecoder = kryoDecoder;
    }

    public static void setLogLevel(int logLevel) {
        PublisherSocketImpl.logLevel = logLevel;
    }

    class SubscriberFluxInfo {
        String modifiedSubject;

        private final Set<String> subscriberNames = ConcurrentHashMap.newKeySet();
        DirectProcessor<Payload> directProcessor;
        Sinks.Many<Payload> sink;

        SubscriberFluxInfo(DirectProcessor<Payload> directProcessor, String unmodifiedSubject) {
            this.directProcessor = directProcessor;
            if (unmodifiedSubject.endsWith("*")) {
                modifiedSubject = unmodifiedSubject.substring(0, unmodifiedSubject.lastIndexOf("*"));
            } else {
                modifiedSubject = unmodifiedSubject;
            }
        }

        SubscriberFluxInfo(Sinks.Many<Payload> sink, String unmodifiedSubject) {
            this.sink = sink;
            if (unmodifiedSubject.endsWith("*")) {
                modifiedSubject = unmodifiedSubject.substring(0, unmodifiedSubject.lastIndexOf("*"));
            } else {
                modifiedSubject = unmodifiedSubject;
            }
        }
        public Set<String> getSubscriberNames() {
            return subscriberNames;
        }
    }

    static class TrieNode {
        final Map<String, TrieNode> children = new ConcurrentHashMap<>();
        final List<SubscriberFluxInfo> wildcardSubscribers = new CopyOnWriteArrayList<>();
    }

    // exact subject subscriptions
    private final Map<String, SubscriberFluxInfo> subjectProcessors = new ConcurrentHashMap<>();

    // wildcard sharing semantics: one shared processor per wildcard subject
    private final Map<String, SubscriberFluxInfo> wildcardSubscriptions = new ConcurrentHashMap<>();

    // fast wildcard routing structure
    private final TrieNode wildcardRoot = new TrieNode();

    private final List<SubscriptionListener> subscriptionListeners = new ArrayList<>();

    @PostConstruct
    void init() {
        if (publisherPort != -1) {
            log.info("Configuring Nimrod RSocket Publisher on port {}", publisherPort);
            RSocketServer.create(this)
                    .bind(TcpServerTransport.create(publisherPort))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
        }
    }

    @Override
    public Mono<RSocket> accept(ConnectionSetupPayload connectionSetupPayload, RSocket rSocket) {
        return Mono.just(new PublisherRSocket(this, kryoDecoder));
    }

    public Flux<Payload> addDirectProcessor(SubscriptionRequest subscriptionRequest) {
        SubscriberFluxInfo subscriberFluxInfo;

        if (subscriptionRequest.isWildcard()) {
            subscriberFluxInfo = wildcardSubscriptions.computeIfAbsent(
                    subscriptionRequest.getSubject(),
                    s -> {
                        SubscriberFluxInfo sfi =
                                new SubscriberFluxInfo(getDirectProcessor(subscriptionRequest), subscriptionRequest.getSubject());
                        insertIntoTrie(subscriptionRequest.getSubject(), sfi);
                        return sfi;
                    }
            );
        } else {
            subscriberFluxInfo = subjectProcessors.computeIfAbsent(
                    subscriptionRequest.getSubject(),
                    s -> new SubscriberFluxInfo(getDirectProcessor(subscriptionRequest), subscriptionRequest.getSubject())
            );
        }

        notifyListeners(subscriptionRequest);
        subscriberFluxInfo.getSubscriberNames().add(subscriptionRequest.getRequestor());

        return subscriberFluxInfo.directProcessor;
    }

    private void insertIntoTrie(String wildcardSubject, SubscriberFluxInfo sfi) {
        String prefix = wildcardSubject.substring(0, wildcardSubject.length() - 1);
        if (prefix.endsWith(".")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }

        TrieNode node = wildcardRoot;
        int start = 0;

        while (true) {
            int dot = prefix.indexOf('.', start);
            String segment = (dot == -1)
                    ? prefix.substring(start)
                    : prefix.substring(start, dot);

            node = node.children.computeIfAbsent(segment, k -> new TrieNode());

            if (dot == -1) {
                break;
            }
            start = dot + 1;
        }

        node.wildcardSubscribers.add(sfi);
    }

    private void removeFromTrie(String wildcardSubject, SubscriberFluxInfo sfi) {
        String prefix = wildcardSubject.substring(0, wildcardSubject.length() - 1);
        if (prefix.endsWith(".")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }

        TrieNode node = wildcardRoot;
        int start = 0;

        while (true) {
            int dot = prefix.indexOf('.', start);
            String segment = (dot == -1)
                    ? prefix.substring(start)
                    : prefix.substring(start, dot);

            node = node.children.get(segment);
            if (node == null) {
                return;
            }

            if (dot == -1) {
                break;
            }
            start = dot + 1;
        }

        node.wildcardSubscribers.remove(sfi);
    }

    private static <E> DirectProcessor<E> getDirectProcessor(SubscriptionRequest subscriptionRequest) {

        DirectProcessor<E> directProcessor = DirectProcessor.create();

        directProcessor.doOnCancel(() ->
                log.debug("Subscription cancelled: {}", subscriptionRequest)
        );

        directProcessor.doOnError(error -> {
            if (error instanceof CancellationException) {
                log.info("Subscription cancelled: {}", subscriptionRequest);
            } else {
                log.error("Subscription error for {} : {}", subscriptionRequest, error.toString(), error);
            }
        });

        directProcessor.doOnComplete(() ->
                log.info("Subscription completed: {}", subscriptionRequest)
        );

        return directProcessor;
    }

    public void removeDirectProcessor(SubscriptionRequest subscriptionRequest) {

        if (subscriptionRequest.isWildcard() || subscriptionRequest.getSubject().endsWith("*")) {

            SubscriberFluxInfo subscriberFluxInfo = wildcardSubscriptions.get(subscriptionRequest.getSubject());

            if (subscriberFluxInfo != null) {
                subscriberFluxInfo.getSubscriberNames().remove(subscriptionRequest.getRequestor());

                if (subscriberFluxInfo.getSubscriberNames().isEmpty()) {
                    subscriberFluxInfo.directProcessor.onComplete();
                    wildcardSubscriptions.remove(subscriptionRequest.getSubject());
                    removeFromTrie(subscriptionRequest.getSubject(), subscriberFluxInfo);

                    log.info("REMOVED wildcard publishing of {} to {}",
                            subscriptionRequest.getSubject(),
                            subscriptionRequest.getRequestor());
                } else {
                    log.info("REDUCED wildcard publishing of {} to {}, {} remain",
                            subscriptionRequest.getSubject(),
                            subscriptionRequest.getRequestor(),
                            subscriberFluxInfo.getSubscriberNames().size());
                }

                notifyListeners(subscriptionRequest);
            }

            return;
        }

        SubscriberFluxInfo subscriberFluxInfo = subjectProcessors.get(subscriptionRequest.getSubject());

        if (subscriberFluxInfo != null) {
            subscriberFluxInfo.getSubscriberNames().remove(subscriptionRequest.getRequestor());

            if (subscriberFluxInfo.getSubscriberNames().isEmpty()) {
                subscriberFluxInfo.directProcessor.onComplete();
                subjectProcessors.remove(subscriptionRequest.getSubject());

                log.info("REMOVED publishing of {} to {}",
                        subscriptionRequest.getSubject(),
                        subscriptionRequest.getRequestor());
            } else {
                log.info("REDUCED publishing of {} to {}, {} remain",
                        subscriptionRequest.getSubject(),
                        subscriptionRequest.getRequestor(),
                        subscriberFluxInfo.getSubscriberNames().size());
            }

            notifyListeners(subscriptionRequest);
        }
    }

    /**
     * If there are currently no subscribers then just return.
     */
    public void publish(String subject, Object data) {
        if (publisherPort == -1) {
            log.warn("publish called subject={} class={} but no nimrod.rsock.publisher.port configured - IGNORED",
                    subject, data.getClass().getSimpleName());
            return;
        }

        if (logLevel > 0) {
            log.info("PUBLISH:[{}]:[{}]", subject, data);
        }

        Payload payloadData = null;
        SubscriberFluxInfo subscriberFluxInfo = subjectProcessors.get(subject);
        HashSet<String> alreadySentSubscribers = new HashSet<>();

        try {
            if (subscriberFluxInfo != null && !subscriberFluxInfo.getSubscriberNames().isEmpty()) {
                PublisherPayload publisherPayload = new PublisherPayload(System.nanoTime(), subject, data);
                payloadData = DefaultPayload.create(kryoEncoder.serialize(publisherPayload));
                subscriberFluxInfo.directProcessor.onNext(payloadData);

                // preserve old duplicate-suppression semantics
                String firstSubscriber =
                        subscriberFluxInfo.getSubscriberNames().iterator().next();

                alreadySentSubscribers.add(firstSubscriber);
            }

            payloadData = dispatchWildcards(subject, data, payloadData, alreadySentSubscribers);

        } finally {
            if (payloadData != null) {
                payloadData.release();
            }
        }
    }

    private Payload dispatchWildcards(
            String subject,
            Object data,
            Payload payloadData,
            Set<String> alreadySentSubscribers
    ) {
        TrieNode node = wildcardRoot;
        int start = 0;

        while (true) {
            int dot = subject.indexOf('.', start);
            String segment = (dot == -1)
                    ? subject.substring(start)
                    : subject.substring(start, dot);

            node = node.children.get(segment);
            if (node == null) {
                return payloadData;
            }

            for (SubscriberFluxInfo sfi : node.wildcardSubscribers) {

                if (!sfi.getSubscriberNames().isEmpty()) {

                    String subscriber = sfi.getSubscriberNames().iterator().next();

                    if (!alreadySentSubscribers.contains(subscriber)) {

                        if (payloadData == null) {
                            PublisherPayload publisherPayload =
                                    new PublisherPayload(System.nanoTime(), subject, data);
                            payloadData = DefaultPayload.create(kryoEncoder.serialize(publisherPayload));
                        }

                        sfi.directProcessor.onNext(payloadData);

                        alreadySentSubscribers.add(subscriber);
                    }
                }
            }
            if (dot == -1) {
                return payloadData;
            }

            start = dot + 1;
        }
    }

    public void addSubscriptionListener(SubscriptionListener listener) {
        subscriptionListeners.add(listener);
    }

    public void notifyListeners(SubscriptionRequest subscriptionRequest) {
        for (SubscriptionListener listener : subscriptionListeners) {
            if (subscriptionRequest.getSubscriptionDirective() == SubscriptionDirective.REQUEST) {
                listener.onSubscription(subscriptionRequest);
            } else if (subscriptionRequest.getSubscriptionDirective() == SubscriptionDirective.CANCEL) {
                listener.onSubscriptionRemove(subscriptionRequest);
            }
        }
    }
}