package com.nimrodtechs.ipcrsock.serialization;



import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

import com.esotericsoftware.kryo.kryo5.Kryo;
import com.esotericsoftware.kryo.kryo5.serializers.FieldSerializer;
import com.esotericsoftware.kryo.kryo5.unsafe.UnsafeInput;
import com.esotericsoftware.kryo.kryo5.unsafe.UnsafeOutput;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.collection.spi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class KryoCommon {
    private static final Logger log = LoggerFactory.getLogger(KryoCommon.class);
    public ThreadLocal<KryoInfo> getKryoThreadLocal() {
        return kryoThreadLocal;
    }

    private final ThreadLocal<KryoInfo> kryoThreadLocal = ThreadLocal.withInitial(() -> {
        log.info("Creating KryoInfo for thread:{}", Thread.currentThread().getName());
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(false);
        kryo.setReferences(true);
        // OR Register your classes here, for better performance and versioning
        // kryo.register(YourClass.class);
        kryo.register(BigDecimal.class);
        kryo.register(Date.class);
        //kryo.register(Class.class, new ClassSerializer());
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

        //Look for application specific property identifying additional DTO classes that will also be serialized


        try {
            kryo.addDefaultSerializer(PersistentIdentifierBag.class, new FieldSerializer(kryo, PersistentIdentifierBag.class));
            kryo.addDefaultSerializer(PersistentBag.class, new FieldSerializer(kryo, PersistentBag.class));
            kryo.addDefaultSerializer(PersistentList.class, new FieldSerializer(kryo, PersistentList.class));
            kryo.addDefaultSerializer(PersistentSet.class, new FieldSerializer(kryo, PersistentSet.class));
            kryo.addDefaultSerializer(PersistentMap.class, new FieldSerializer(kryo, PersistentMap.class));
            kryo.addDefaultSerializer(PersistentSortedMap.class, new FieldSerializer(kryo, PersistentSortedMap.class));
            kryo.addDefaultSerializer(PersistentSortedSet.class, new FieldSerializer(kryo, PersistentSortedSet.class));
        } catch (NoClassDefFoundError e) {
            //Don't care ...just means hibernate entities containing collection classes can't be deserialized

        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(KryoInfo.MAX_MESSAGE_SIZE);

        return new KryoInfo(kryo,outputStream,
                new UnsafeOutput(outputStream,KryoInfo.MAX_MESSAGE_SIZE),
                new UnsafeInput());
    });


}
