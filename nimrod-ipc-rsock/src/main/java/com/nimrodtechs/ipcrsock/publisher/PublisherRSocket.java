package com.nimrodtechs.ipcrsock.publisher;

import com.nimrodtechs.ipcrsock.serialization.KryoDecoder;
import com.nimrodtechs.ipcrsock.common.SubscriptionRequest;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class PublisherRSocket implements RSocket {

    final PublisherSocketImpl publisherSocket;
    final KryoDecoder kryoDecoder;

    public PublisherRSocket(PublisherSocketImpl publisherSocket, KryoDecoder kryoDecoder) {
        this.publisherSocket = publisherSocket;
        this.kryoDecoder = kryoDecoder;
    }

    @Override
    public Mono<Void> fireAndForget(Payload payload) {
        try {
            byte[] bytes = new byte[payload.data().readableBytes()];
            payload.data().readBytes(bytes);
            SubscriptionRequest subscriptionRequest = kryoDecoder.deserialize(bytes, SubscriptionRequest.class);
            publisherSocket.removeDirectProcessor(subscriptionRequest);
        } finally {
            try { payload.release(); }
            catch (Throwable ignore) {}
        }
        return Mono.empty();
    }


    @Override
    public Mono<Payload> requestResponse(Payload payload) {
        // Implementation here
        return Mono.empty();
    }

    /**
     * This is the entry point for the Publisher whenever a client requests a stream/Subscribes to a subject
     * @param payload
     * @return
     */
    @Override
    public Flux<Payload> requestStream(Payload   payload) {
        try {
            byte[] bytes = new byte[payload.data().readableBytes()];
            payload.data().readBytes(bytes);

            SubscriptionRequest subscriptionRequest =
                    kryoDecoder.deserialize(bytes, SubscriptionRequest.class);

            return publisherSocket.addDirectProcessor(subscriptionRequest);
        } finally {
            payload.release();
        }
    }

}
