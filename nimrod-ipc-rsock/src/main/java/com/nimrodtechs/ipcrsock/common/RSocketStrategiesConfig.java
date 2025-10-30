package com.nimrodtechs.ipcrsock.common;

import com.nimrodtechs.ipcrsock.serialization.KryoCommon;
import com.nimrodtechs.ipcrsock.serialization.KryoDecoder;
import com.nimrodtechs.ipcrsock.serialization.KryoEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.rsocket.RSocketStrategies;

@Configuration
public class RSocketStrategiesConfig {
    private final KryoCommon kryoCommon;
    private final KryoEncoder kryoEncoder;
    private final KryoDecoder kryoDecoder;

    public RSocketStrategiesConfig(KryoCommon kryoCommon, KryoEncoder kryoEncoder, KryoDecoder kryoDecoder) {
        this.kryoCommon = kryoCommon;
        this.kryoEncoder = kryoEncoder;
        this.kryoDecoder = kryoDecoder;
    }

    @Bean
    public RSocketStrategies rSocketStrategies() {
        return RSocketStrategies.builder()
                .encoders(encoders -> encoders.add(kryoEncoder))
                .decoders(decoders -> decoders.add(kryoDecoder))
                .build();
    }
}
