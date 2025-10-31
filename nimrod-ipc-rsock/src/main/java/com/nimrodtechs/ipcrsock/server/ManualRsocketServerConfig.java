package com.nimrodtechs.ipcrsock.server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.rsocket.RSocketStrategies;
import org.springframework.messaging.rsocket.annotation.support.RSocketMessageHandler;

/**
 * Use this if the RsocketServer port is NOT fixed and available via properties/vm param -Dnimrod.rsock.server.port=nnnn
 * and needs to be supplied programmatically and RsocketServer started manually.
 * It is enabled by setting spring profile manualrsockserver.
 */
@Configuration
@Profile("manualrsockserver & !gui")
public class ManualRsocketServerConfig {
    @Bean
    ManualRsocketServer ManualRsocketServerFactory(RSocketStrategies rSocketStrategies, RSocketMessageHandler messageHandler) {
        return new ManualRsocketServer(rSocketStrategies,messageHandler);
    }
}
