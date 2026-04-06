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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    public int getPublisherPort() {
        return publisherPort;
    }
    public void setPublisherPort(int publisherPort) {
        this.publisherPort = publisherPort;
    }

    private static int logLevel = 0;

    private static final Sinks.EmitFailureHandler emitFailureHandler =
            Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(500));

    /**
     * Tracks all subscription requests made on a given connection so they can be removed if the connection closes.
     */
    private final Map<RSocket, Set<SubscriptionRequest>> connectionSubscriptions =
            new ConcurrentHashMap<>();

    /**
     * Enforce one live socket connection per subscriber requestor name.
     */
    private final Map<String, RSocket> activeSubscribers =
            new ConcurrentHashMap<>();

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
        Sinks.Many<Payload> sink;

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

    // wildcard sharing semantics: one shared sink per wildcard subject
    private final Map<String, SubscriberFluxInfo> wildcardSubscriptions = new ConcurrentHashMap<>();

    // fast wildcard routing structure
    private final TrieNode wildcardRoot = new TrieNode();

    private final List<SubscriptionListener> subscriptionListeners = new ArrayList<>();

    @PostConstruct
    void init() {
        if (publisherPort != -1) {
            start(publisherPort);
        } else {
            log.info("No Nimrod RSocket Publisher configured. Assume will be supplied via start() method");
        }
    }

    public void start(int port) {
        this.publisherPort = port;

        log.info("Configuring Nimrod RSocket Publisher on port {}", publisherPort);
        RSocketServer.create(this)
                .bind(TcpServerTransport.create(publisherPort))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    @Override
    public Mono<RSocket> accept(ConnectionSetupPayload connectionSetupPayload, RSocket rSocket) {
        log.info("Subscriber connection established");

        rSocket.onClose()
                .doFinally(signal -> {
                    log.info("Subscriber connection closed: {}", signal);
                    cleanupConnection(rSocket);
                })
                .subscribe();

        return Mono.just(new PublisherRSocket(this, kryoDecoder, rSocket));
    }

    public Flux<Payload> addDirectProcessor(SubscriptionRequest subscriptionRequest, RSocket connection) {
        String requestor = subscriptionRequest.getRequestor();

        // Enforce single connection per requestor (atomic + safe)
        activeSubscribers.compute(requestor, (key, existingConnection) -> {

            if (existingConnection != null && existingConnection != connection) {
                log.warn("Duplicate connection detected for requestor {} - cleaning up previous connection", requestor);

                // CRITICAL: force cleanup BEFORE replacing connection
                cleanupConnection(existingConnection);

                try {
                    existingConnection.dispose();
                } catch (Exception e) {
                    log.warn("Error closing previous connection for requestor {}", requestor, e);
                }
            }

            return connection;
        });

        SubscriberFluxInfo subscriberFluxInfo;

        if (subscriptionRequest.isWildcard()) {
            subscriberFluxInfo = wildcardSubscriptions.computeIfAbsent(
                    subscriptionRequest.getSubject(),
                    s -> {
                        SubscriberFluxInfo sfi =
                                new SubscriberFluxInfo(getSink(), subscriptionRequest.getSubject());
                        insertIntoTrie(subscriptionRequest.getSubject(), sfi);
                        return sfi;
                    }
            );
        } else {
            subscriberFluxInfo = subjectProcessors.computeIfAbsent(
                    subscriptionRequest.getSubject(),
                    s -> new SubscriberFluxInfo(getSink(), subscriptionRequest.getSubject())
            );
        }

        notifyListeners(subscriptionRequest, false);
        subscriberFluxInfo.getSubscriberNames().add(subscriptionRequest.getRequestor());

        connectionSubscriptions
                .computeIfAbsent(connection, k -> ConcurrentHashMap.newKeySet())
                .add(subscriptionRequest);

        return subscriberFluxInfo.sink.asFlux()
                .doOnCancel(() -> log.debug("Subscription cancelled: {}", subscriptionRequest))
                .doOnError(error -> {
                    if (error instanceof CancellationException) {
                        log.info("Subscription cancelled: {}", subscriptionRequest);
                    } else {
                        log.error("Subscription error for {} : {}", subscriptionRequest, error.toString(), error);
                    }
                })
                .doOnComplete(() -> log.info("Subscription completed: {}", subscriptionRequest));
    }


    private void insertIntoTrie(String wildcardSubject, SubscriberFluxInfo subscriberFluxInfo) {
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

        node.wildcardSubscribers.add(subscriberFluxInfo);
    }

    private void removeFromTrie(String wildcardSubject, SubscriberFluxInfo subscriberFluxInfo) {
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

        node.wildcardSubscribers.remove(subscriberFluxInfo);
    }

    private static Sinks.Many<Payload> getSink() {
        return Sinks.many().multicast().onBackpressureBuffer();
    }

    public void removeDirectProcessor(SubscriptionRequest subscriptionRequest, boolean isDisconnect) {

        if (subscriptionRequest.isWildcard() || subscriptionRequest.getSubject().endsWith("*")) {

            SubscriberFluxInfo subscriberFluxInfo = wildcardSubscriptions.get(subscriptionRequest.getSubject());

            if (subscriberFluxInfo != null) {
                subscriberFluxInfo.getSubscriberNames().remove(subscriptionRequest.getRequestor());

                if (subscriberFluxInfo.getSubscriberNames().isEmpty()) {
                    subscriberFluxInfo.sink.tryEmitComplete();
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

                notifyListeners(subscriptionRequest, isDisconnect);
            }

            return;
        }

        SubscriberFluxInfo subscriberFluxInfo = subjectProcessors.get(subscriptionRequest.getSubject());

        if (subscriberFluxInfo != null) {
            subscriberFluxInfo.getSubscriberNames().remove(subscriptionRequest.getRequestor());

            if (subscriberFluxInfo.getSubscriberNames().isEmpty()) {
                subscriberFluxInfo.sink.tryEmitComplete();
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

            notifyListeners(subscriptionRequest, isDisconnect);
        }
    }

    private void cleanupConnection(RSocket connection) {

        Set<SubscriptionRequest> subs = connectionSubscriptions.remove(connection);

        activeSubscribers.entrySet().removeIf(entry -> entry.getValue() == connection);

        if (subs == null) {
            return;
        }

        log.info("Cleaning {} subscriptions for closed connection", subs.size());

        for (SubscriptionRequest sub : subs) {
            removeDirectProcessor(sub, true);
        }
    }

    /**
     * If there are currently no subscribers, then just return.
     */
    public void publish(String subject, Object data) {
        if (publisherPort == -1) {
            log.warn("publish called with subject={} class={} but no nimrod.rsock.publisher.port configured - IGNORED",
                    subject, data.getClass().getSimpleName());
            return;
        }
        if(subject == null || subject.isEmpty() || subject.charAt(subject.length() - 1) == '*') {
            log.warn("publish called with subject[{}] but subject fails validation - IGNORED",
                    subject);
            return;
        }
        if (logLevel > 0) {
            log.info("PUBLISH:[{}]:[{}]", subject, data);
        }

        Set<SubscriberFluxInfo> alreadySentProcessors = new HashSet<>();

        SubscriberFluxInfo subscriberFluxInfo = subjectProcessors.get(subject);

        if (subscriberFluxInfo != null && !subscriberFluxInfo.getSubscriberNames().isEmpty()) {
            if (alreadySentProcessors.add(subscriberFluxInfo)) {
                PublisherPayload publisherPayload =
                        new PublisherPayload(System.nanoTime(), subject, data);

                Payload payloadData =
                        DefaultPayload.create(kryoEncoder.serialize(publisherPayload));

                subscriberFluxInfo.sink.emitNext(payloadData, emitFailureHandler);
            }
        }

        dispatchWildcards(subject, data, alreadySentProcessors);
    }

    private void dispatchWildcards(
            String subject,
            Object data,
            Set<SubscriberFluxInfo> alreadySentProcessors
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
                return;
            }

            for (SubscriberFluxInfo subscriberFluxInfo : node.wildcardSubscribers) {
                if (!subscriberFluxInfo.getSubscriberNames().isEmpty()) {
                    if (alreadySentProcessors.add(subscriberFluxInfo)) {
                        PublisherPayload publisherPayload =
                                new PublisherPayload(System.nanoTime(), subject, data);

                        Payload payloadData =
                                DefaultPayload.create(kryoEncoder.serialize(publisherPayload));

                        subscriberFluxInfo.sink.emitNext(payloadData, emitFailureHandler);
                    }
                }
            }

            if (dot == -1) {
                return;
            }

            start = dot + 1;
        }
    }

    public void addSubscriptionListener(SubscriptionListener listener) {
        subscriptionListeners.add(listener);
    }

    public void notifyListeners(SubscriptionRequest subscriptionRequest, boolean isDisconnect) {
        for (SubscriptionListener listener : subscriptionListeners) {
            if (subscriptionRequest.getSubscriptionDirective() == SubscriptionDirective.CANCEL || isDisconnect) {
                listener.onSubscriptionRemove(subscriptionRequest);
            } else if (subscriptionRequest.getSubscriptionDirective() == SubscriptionDirective.REQUEST) {
                listener.onSubscription(subscriptionRequest);
            }
        }
    }
}
