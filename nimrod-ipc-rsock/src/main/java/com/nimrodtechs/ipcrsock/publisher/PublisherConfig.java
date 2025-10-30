package com.nimrodtechs.ipcrsock.publisher;

import com.nimrodtechs.ipcrsock.serialization.KryoCommon;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = {"com.nimrodtechs.ipcrsock.common","com.nimrodtechs.ipcrsock.serialization","com.nimrodtechs.ipcrsock.actuator"})
public class PublisherConfig {
    private final KryoCommon kryoCommon;
    public PublisherConfig(KryoCommon kryoCommon) {
        this.kryoCommon = kryoCommon;
    }

}
