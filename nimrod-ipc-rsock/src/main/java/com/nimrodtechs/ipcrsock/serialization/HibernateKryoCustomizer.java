package com.nimrodtechs.ipcrsock.serialization;

import com.esotericsoftware.kryo.kryo5.Kryo;
import com.esotericsoftware.kryo.kryo5.serializers.FieldSerializer;
import org.hibernate.collection.spi.*;
import org.springframework.stereotype.Component;

@Component
public class HibernateKryoCustomizer implements KryoCustomizer {

    @Override
    public void customize(Kryo kryo) {
        try {
            kryo.addDefaultSerializer(
                    PersistentIdentifierBag.class,
                    new FieldSerializer<>(kryo, PersistentIdentifierBag.class)
            );
            kryo.addDefaultSerializer(
                    PersistentBag.class,
                    new FieldSerializer<>(kryo, PersistentBag.class)
            );
            kryo.addDefaultSerializer(
                    PersistentList.class,
                    new FieldSerializer<>(kryo, PersistentList.class)
            );
            kryo.addDefaultSerializer(
                    PersistentSet.class,
                    new FieldSerializer<>(kryo, PersistentSet.class)
            );
            kryo.addDefaultSerializer(
                    PersistentMap.class,
                    new FieldSerializer<>(kryo, PersistentMap.class)
            );
            kryo.addDefaultSerializer(
                    PersistentSortedMap.class,
                    new FieldSerializer<>(kryo, PersistentSortedMap.class)
            );
            kryo.addDefaultSerializer(
                    PersistentSortedSet.class,
                    new FieldSerializer<>(kryo, PersistentSortedSet.class)
            );

        } catch (NoClassDefFoundError e) {
            // Hibernate not present → ignore
        }
    }
}