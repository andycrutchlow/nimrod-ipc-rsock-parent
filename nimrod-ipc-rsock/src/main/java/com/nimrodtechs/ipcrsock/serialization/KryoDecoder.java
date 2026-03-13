package com.nimrodtechs.ipcrsock.serialization;

import org.reactivestreams.Publisher;
import org.springframework.core.ResolvableType;
import org.springframework.core.codec.Decoder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Component
public class KryoDecoder implements Decoder<Object> {

    private static final MimeType KRYO = MimeType.valueOf("application/x-kryo");

    private final KryoCommon kryoCommon;
    private final List<MimeType> mimeTypes = List.of(KRYO);

    public KryoDecoder(KryoCommon kryoCommon) {
        this.kryoCommon = kryoCommon;
    }

    @Override
    public boolean canDecode(ResolvableType elementType, MimeType mimeType) {
        return KRYO.equals(mimeType);
    }

    @Override
    public List<MimeType> getDecodableMimeTypes() {
        return mimeTypes;
    }

    /**
     * Synchronous decode (single DataBuffer)
     */
    @Override
    public Object decode(
            DataBuffer dataBuffer,
            ResolvableType targetType,
            MimeType mimeType,
            Map<String, Object> hints
    ) {

        byte[] bytes = new byte[dataBuffer.readableByteCount()];
        dataBuffer.read(bytes);

        DataBufferUtils.release(dataBuffer);

        KryoInfo ki = kryoCommon.tryBorrow();

        try {
            ki.getInput().setBuffer(bytes);
            ki.getInput().reset();

            return ki.getKryo()
                    .readObjectOrNull(ki.getInput(), targetType.resolve());

        } finally {
            kryoCommon.release(ki);
        }
    }

    /**
     * Reactive streaming decode
     * Preserves ordering.
     */
    @Override
    public Flux<Object> decode(
            Publisher<DataBuffer> dataBuffers,
            ResolvableType elementType,
            MimeType mimeType,
            Map<String, Object> hints
    ) {

        final Class<?> resolved = elementType.resolve();

        return Flux.from(dataBuffers)
                .concatMap(dataBuffer ->
                        Mono.defer(() -> {

                            byte[] bytes = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(bytes);

                            DataBufferUtils.release(dataBuffer);

                            KryoInfo ki = kryoCommon.tryBorrow();

                            try {
                                ki.getInput().setBuffer(bytes);
                                ki.getInput().reset();

                                Object result =
                                        ki.getKryo().readObjectOrNull(ki.getInput(), resolved);

                                return Mono.just(result)
                                        .doFinally(signal -> kryoCommon.release(ki));

                            } catch (Throwable t) {
                                kryoCommon.release(ki);
                                return Mono.error(t);
                            }
                        })
                );
    }

    /**
     * Mono decode (used by WebFlux in some paths)
     */
    @Override
    public Mono<Object> decodeToMono(
            Publisher<DataBuffer> inputStream,
            ResolvableType elementType,
            MimeType mimeType,
            Map<String, Object> hints
    ) {

        final Class<?> resolved = elementType.resolve();

        return DataBufferUtils.join(Flux.from(inputStream))
                .flatMap(dataBuffer -> Mono.defer(() -> {

                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);

                    DataBufferUtils.release(dataBuffer);

                    KryoInfo ki = kryoCommon.tryBorrow();

                    try {
                        ki.getInput().setBuffer(bytes);
                        ki.getInput().reset();

                        Object result =
                                ki.getKryo().readObjectOrNull(ki.getInput(), resolved);

                        return Mono.just(result)
                                .doFinally(signal -> kryoCommon.release(ki));

                    } catch (Throwable t) {
                        kryoCommon.release(ki);
                        return Mono.error(t);
                    }
                }));
    }

    /**
     * Utility helper
     */
    public <T> T deserialize(byte[] bytes, Class<T> type) {

        KryoInfo ki = kryoCommon.tryBorrow();

        try {
            ki.getInput().setBuffer(bytes);
            ki.getInput().reset();

            return ki.getKryo().readObjectOrNull(ki.getInput(), type);

        } finally {
            kryoCommon.release(ki);
        }
    }
}