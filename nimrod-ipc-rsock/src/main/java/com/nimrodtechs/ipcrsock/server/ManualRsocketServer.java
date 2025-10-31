package com.nimrodtechs.ipcrsock.server;

import io.rsocket.core.RSocketServer;
import io.rsocket.transport.netty.server.TcpServerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.rsocket.RSocketStrategies;
import org.springframework.messaging.rsocket.annotation.support.RSocketMessageHandler;

import java.util.concurrent.CountDownLatch;

public class ManualRsocketServer {
    private static final Logger log = LoggerFactory.getLogger(ManualRsocketServer.class);
    private RSocketServer rSocketServer;

    ManualRsocketServer(RSocketStrategies rSocketStrategies, RSocketMessageHandler messageHandler) {
        messageHandler.setRSocketStrategies(rSocketStrategies);
        rSocketServer = RSocketServer.create(messageHandler.responder());
    }
    public void startListeningWithPort(int port) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] errorHolder = new Throwable[1];
        rSocketServer.bind(TcpServerTransport.create(port)).subscribe(success -> latch.countDown(), // Signal success
                error -> {
                    errorHolder[0] = error; // Store the error
                    latch.countDown(); // Signal completion
                }
        );

        // Wait for the server setup to complete (either success or error)
        latch.await();
        if (errorHolder[0] != null) {
            // Propagate the error as a runtime exception
            // or handle it as per your application's needs
            throw new RuntimeException("Failed to start Manual RSocketServer Netty started on port "+port, errorHolder[0]);
        }
        log.info("Manual RSocketServer Netty started on port: "+port);
    }
}
