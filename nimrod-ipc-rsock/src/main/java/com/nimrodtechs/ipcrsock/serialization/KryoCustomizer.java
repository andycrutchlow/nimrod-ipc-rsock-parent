package com.nimrodtechs.ipcrsock.serialization;

import com.esotericsoftware.kryo.kryo5.Kryo;

public interface KryoCustomizer {
    void customize(Kryo kryo);
}
