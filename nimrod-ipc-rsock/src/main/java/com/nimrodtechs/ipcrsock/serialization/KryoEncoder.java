package com.nimrodtechs.ipcrsock.serialization;

import com.esotericsoftware.kryo.kryo5.Kryo;
import org.reactivestreams.Publisher;
import org.springframework.core.ResolvableType;
import org.springframework.core.codec.Encoder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

@Component
public class KryoEncoder implements Encoder<Object> {

    private static final MimeType KRYO = MimeType.valueOf("application/x-kryo");

    private final KryoCommon kryoCommon;
    private final List<MimeType> mimeTypes = List.of(KRYO);

    public KryoEncoder(KryoCommon kryoCommon) {
        this.kryoCommon = kryoCommon;
    }

    @Override
    public boolean canEncode(ResolvableType elementType, MimeType mimeType) {
        return KRYO.equals(mimeType);
    }

    @Override
    public List<MimeType> getEncodableMimeTypes() {
        return mimeTypes;
    }

    /**
     * Reactive stream encoding (RSocket/WebFlux streaming).
     * Uses ZERO-COPY wrapping via ByteBuffer.
     * Resource lifecycle tied to subscriber termination.
     */
    @Override
    public Flux<DataBuffer> encode(
            Publisher<?> inputStream,
            DataBufferFactory bufferFactory,
            ResolvableType elementType,
            MimeType mimeType,
            Map<String, Object> hints
    ) {

        final Class<?> resolved = elementType.resolve();

        return Flux.from(inputStream)
                .concatMap(value ->
                        Mono.defer(() -> {

                            KryoInfo ki = kryoCommon.tryBorrow();

                            try {
                                Kryo kryo = ki.getKryo();

                                kryo.writeObjectOrNull(ki.getOutput(), value, resolved);
                                ki.getOutput().flush();

                                ReusableByteArrayOutputStream out = ki.getOutputStream();

                                ByteBuffer bb = ByteBuffer.wrap(
                                        out.buffer(),
                                        0,
                                        out.size()
                                );

                                DataBuffer buffer = bufferFactory.wrap(bb);

                                // Release only after subscriber terminates
                                return Mono.just(buffer)
                                        .doFinally(signal -> kryoCommon.release(ki));

                            } catch (Throwable t) {
                                kryoCommon.release(ki);
                                return Mono.error(t);
                            }
                        })
                );
    }

    /**
     * Single-value encoding (MVC / non-stream case).
     *
     * Uses SAFE COPY via toByteArray().
     * This avoids lifecycle hazards in synchronous usage.
     */
    @Override
    public DataBuffer encodeValue(
            Object object,
            DataBufferFactory bufferFactory,
            ResolvableType valueType,
            MimeType mimeType,
            Map<String, Object> hints
    ) {

        KryoInfo ki = kryoCommon.tryBorrow();

        try {
            Kryo kryo = ki.getKryo();

            if (object == null) {
                kryo.writeObject(ki.getOutput(), Kryo.NULL);
            } else {
                kryo.writeObjectOrNull(ki.getOutput(), object, object.getClass());
            }

            ki.getOutput().flush();

            // COPY (safe, no shared memory exposure)
            byte[] bytes = ki.getOutputStream().toByteArray();

            return bufferFactory.wrap(bytes);

        } finally {
            kryoCommon.release(ki);
        }
    }

    /**
     * Utility helper.
     * Uses COPY for safety.
     */
    public byte[] serialize(Object object) {

        KryoInfo ki = kryoCommon.tryBorrow();

        try {
            Kryo kryo = ki.getKryo();

            if (object == null) {
                kryo.writeObject(ki.getOutput(), Kryo.NULL);
            } else {
                kryo.writeObjectOrNull(ki.getOutput(), object, object.getClass());
            }

            ki.getOutput().flush();

            return ki.getOutputStream().toByteArray();

        } finally {
            kryoCommon.release(ki);
        }
    }
}