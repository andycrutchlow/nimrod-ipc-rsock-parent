package com.nimrodtechs.ipcrsock.serialization;

import com.esotericsoftware.kryo.kryo5.Kryo;
import com.esotericsoftware.kryo.kryo5.unsafe.UnsafeInput;
import com.esotericsoftware.kryo.kryo5.unsafe.UnsafeOutput;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

@Component
public class KryoCommon {

    private final BlockingQueue<KryoInfo> pool;

    public KryoCommon(
            @Value("${nimrod.kryo.poolSize:16}") int poolSize
    ) {
        this.pool = new ArrayBlockingQueue<>(poolSize);

        for (int i = 0; i < poolSize; i++) {
            pool.add(createKryoInfo());
        }
    }

    private KryoInfo createKryoInfo() {
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(false);
        kryo.setReferences(true);

        kryo.register(BigDecimal.class);
        kryo.register(Date.class);
        kryo.register(HashMap.class);
        kryo.register(HashSet.class);
        kryo.register(Boolean[].class);
        kryo.register(Double[].class);
        kryo.register(Float[].class);
        kryo.register(Integer[].class);
        kryo.register(Long[].class);
        kryo.register(Short[].class);
        kryo.register(String[].class);
        kryo.register(Date[].class);
        kryo.register(BigDecimal[].class);
        kryo.register(BigInteger[].class);
        kryo.register(Class[].class);
        kryo.register(Object[].class);
        kryo.register(ArrayList.class);
        kryo.register(TreeMap.class);
        kryo.register(boolean[].class);
        kryo.register(double[].class);
        kryo.register(float[].class);
        kryo.register(int[].class);
        kryo.register(long[].class);
        kryo.register(short[].class);
        kryo.register(byte[].class);
        kryo.register(TreeSet.class);

        ReusableByteArrayOutputStream outputStream =
                new ReusableByteArrayOutputStream(KryoInfo.KRYO_INITIAL_DATA_SIZE);

        return new KryoInfo(
                kryo,
                outputStream,
                new UnsafeOutput(outputStream, KryoInfo.KRYO_INITIAL_DATA_SIZE),
                new UnsafeInput()
        );
    }

    public KryoInfo borrow() throws InterruptedException {
        return pool.take();
    }

    public KryoInfo tryBorrow() {
        KryoInfo info = pool.poll();
        return (info != null) ? info : createKryoInfo(); // fallback burst allocation
    }

    public void release(KryoInfo info) {
        info.getKryo().reset();
        info.getOutputStream().reset();
        info.getOutput().reset(); // do it here, consistently
        // If pool is full (because this was a fallback instance), just drop it.
        pool.offer(info);
    }
}