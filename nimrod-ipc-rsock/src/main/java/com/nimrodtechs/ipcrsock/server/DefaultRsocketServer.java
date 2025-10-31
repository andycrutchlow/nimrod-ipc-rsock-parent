package com.nimrodtechs.ipcrsock.server;

import io.rsocket.core.RSocketServer;
import io.rsocket.transport.netty.server.CloseableChannel;
import io.rsocket.transport.netty.server.TcpServerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.rsocket.annotation.support.RSocketMessageHandler;
import reactor.core.publisher.Mono;
import reactor.netty.tcp.TcpServer;

import java.net.InetSocketAddress;

@Configuration
@Profile("!manualrsockserver")
public class DefaultRsocketServer implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DefaultRsocketServer.class);

    private CloseableChannel serverChannel;

    @Value("${nimrod.rsock.server.host:0.0.0.0}")
    private String host;

    @Value("${nimrod.rsock.server.port}")
    private int port;

    private final RSocketServer rSocketServer;

    public DefaultRsocketServer(RSocketServer rSocketServer) {
        this.rSocketServer = rSocketServer;
    }
    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void startTcpRSocketServer() {
        log.info("Starting RSocket TCP server on {}:{}", host, port);

        serverChannel = rSocketServer
                .bind(TcpServerTransport.create(
                        TcpServer.create()
                                .host(host)
                                .port(port)))
                .doOnNext(ch -> log.info("RSocket TCP server started on {}:{}",
                        ((InetSocketAddress) ch.address()).getHostString(),
                        ((InetSocketAddress) ch.address()).getPort()))
                .block();

        if (serverChannel == null) {
            throw new IllegalStateException("Failed to start RSocket TCP server on port " + port);
        }
    }

    @Override
    public void destroy() {
        if (serverChannel != null) {
            log.info("Shutting down RSocket TCP server...");
            try {
                serverChannel.dispose();
                serverChannel.onClose().block();
            } catch (Exception e) {
                log.warn("Error during RSocket TCP server shutdown", e);
            }
        }
    }
}