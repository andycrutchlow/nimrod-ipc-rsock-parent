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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class KryoEncoder implements Encoder<Object> {

    private static KryoCommon kryoCommon;

    List<MimeType> mimeTypes = new ArrayList<>();

    public KryoEncoder(KryoCommon kryoCommon) {
        this.kryoCommon = kryoCommon;
        mimeTypes.add(MimeType.valueOf("application/x-kryo"));
    }
    @Override
    public boolean canEncode(ResolvableType elementType, MimeType mimeType) {
        return MimeType.valueOf("application/x-kryo").equals(mimeType);
    }

    @Override
    public List<MimeType> getEncodableMimeTypes() {
        return mimeTypes;
    }

    @Override
    public List<MimeType> getEncodableMimeTypes(ResolvableType elementType) {
        return Encoder.super.getEncodableMimeTypes(elementType);
    }

    @Override
    public Flux<DataBuffer> encode(Publisher<?> inputStream, DataBufferFactory bufferFactory, ResolvableType elementType, MimeType mimeType, Map<String, Object> hints) {
        return Flux.from(inputStream)
                .map(bytes -> {
                    KryoInfo kryoInfo = kryoCommon.getKryoThreadLocal().get();
                    final Kryo kryo = kryoInfo.getKryo();
                    kryoInfo.getOutput().reset();
                    kryoInfo.getOutputStream().reset();
                    kryo.writeObjectOrNull(kryoInfo.getOutput(), bytes,elementType.resolve());
                    kryoInfo.getOutput().flush();
                    return bufferFactory.wrap(kryoInfo.getOutputStream().toByteArray());
                });
    }

    @Override
    public DataBuffer encodeValue(Object object, DataBufferFactory bufferFactory, ResolvableType valueType, MimeType mimeType, Map<String, Object> hints) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        KryoInfo kryoInfo = kryoCommon.getKryoThreadLocal().get();
        final Kryo kryo = kryoInfo.getKryo();
        kryoInfo.getOutput().reset();
        kryoInfo.getOutputStream().reset();
        if (object == null) {
            kryo.writeObject(kryoInfo.getOutput(), Kryo.NULL);
        }
        else {
            kryo.writeObjectOrNull(kryoInfo.getOutput(), object,object.getClass());
        }
        kryoInfo.getOutput().flush();
        return bufferFactory.wrap(kryoInfo.getOutputStream().toByteArray());
    }

    public static byte[] serialize(final Object object) {
//        if (object instanceof String) {
//            return ((String) object).getBytes();
//        }
        KryoInfo kryoInfo = kryoCommon.getKryoThreadLocal().get();
        final Kryo kryo = kryoInfo.getKryo();
        kryoInfo.getOutput().reset();
        kryoInfo.getOutputStream().reset();
        if (object == null) {
            kryo.writeObject(kryoInfo.getOutput(), Kryo.NULL);
        }
        else {
            kryo.writeObjectOrNull(kryoInfo.getOutput(), object, object.getClass());
        }
        kryoInfo.getOutput().flush();
        return kryoInfo.getOutputStream().toByteArray();
    }

}
