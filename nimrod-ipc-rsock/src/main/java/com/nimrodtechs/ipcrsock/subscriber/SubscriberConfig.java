package com.nimrodtechs.ipcrsock.subscriber;

import com.nimrodtechs.ipcrsock.serialization.KryoCommon;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = {"com.nimrodtechs.ipcrsock.common","com.nimrodtechs.ipcrsock.serialization"})
public class SubscriberConfig {
    private final KryoCommon kryoCommon;
    public SubscriberConfig(KryoCommon kryoCommon) {
        this.kryoCommon = kryoCommon;
    }

}

