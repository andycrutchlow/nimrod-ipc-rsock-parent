package com.nimrodtechs.ipcrsock.server;

import io.rsocket.core.RSocketServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.rsocket.annotation.support.RSocketMessageHandler;

@Configuration
@Profile("!manualrsockserver")
public class DefaultRsocketServerConfig {

    @Bean
    public RSocketServer nimrodRSocketServer(RSocketMessageHandler messageHandler) {
        return RSocketServer.create(messageHandler.responder());
    }
}