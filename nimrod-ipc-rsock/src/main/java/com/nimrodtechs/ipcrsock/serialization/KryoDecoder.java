package com.nimrodtechs.ipcrsock.serialization;

import org.reactivestreams.Publisher;
import org.springframework.core.ResolvableType;
import org.springframework.core.codec.Decoder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
@Component
public class KryoDecoder implements Decoder<Object> {

    private final KryoCommon kryoCommon;
    List<MimeType> mimeTypes = new ArrayList<>();

    public KryoDecoder(KryoCommon kryoCommon) {
        this.kryoCommon = kryoCommon;
        mimeTypes.add(MimeType.valueOf("application/x-kryo"));
    }

    public <T> T deserialize(byte[] b, Class<T> c) {
//        if (String.class.equals(c)) {
//            return (T) new String(b);
//        }
        KryoInfo kryoInfo = kryoCommon.getKryoThreadLocal().get();
        kryoInfo.getInput().setBuffer(b);
        kryoInfo.getInput().reset();
        return (T)kryoInfo.getKryo().readObjectOrNull(kryoInfo.getInput(),c);
    }

    @Override
    public boolean canDecode(ResolvableType elementType, MimeType mimeType) {
        return MimeType.valueOf("application/x-kryo").equals(mimeType);
    }
    @Override
    public Object decode(DataBuffer dataBuffer, ResolvableType targetType, MimeType mimeType, Map<String, Object> hints) {
        byte[] bytes = new byte[dataBuffer.readableByteCount()];
        dataBuffer.read(bytes);
        //Release the underlying pooled ByteBuf once we’ve copied the data out
        if (dataBuffer instanceof NettyDataBuffer nettyBuf) {
            nettyBuf.getNativeBuffer().release();
        }
        KryoInfo kryoInfo = kryoCommon.getKryoThreadLocal().get();
        kryoInfo.getInput().setBuffer(bytes);
        kryoInfo.getInput().reset();
        return kryoInfo.getKryo().readObjectOrNull(kryoInfo.getInput(),targetType.resolve());
    }

    @Override
    public List<MimeType> getDecodableMimeTypes() {
        return mimeTypes;
    }

    @Override
    public List<MimeType> getDecodableMimeTypes(ResolvableType targetType) {
        return Decoder.super.getDecodableMimeTypes(targetType);
    }

    @Override
    public Flux<Object> decode(Publisher<DataBuffer> dataBuffers, ResolvableType elementType, MimeType mimeType, Map<String, Object> hints) {
        return Flux.from(dataBuffers)
                .map(dataBuffer -> {
                    KryoInfo kryoInfo = kryoCommon.getKryoThreadLocal().get();
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    //Release the underlying pooled ByteBuf once we’ve copied the data out
                    if (dataBuffer instanceof NettyDataBuffer nettyBuf) {
                        nettyBuf.getNativeBuffer().release();
                    }
                    kryoInfo.getInput().setBuffer(bytes);
                    kryoInfo.getInput().reset();
                    return kryoInfo.getKryo().readObjectOrNull(kryoInfo.getInput(),elementType.resolve());
                });
    }

    @Override
    public Mono<Object> decodeToMono(Publisher<DataBuffer> inputStream, ResolvableType elementType, MimeType mimeType, Map<String, Object> hints) {
        return null;
    }
}

