package com.nimrodtechs.ipcrsock.common;

import com.nimrodtechs.ipcrsock.client.RemoteServerService;
import com.nimrodtechs.ipcrsock.publisher.PublisherSocketImpl;
import com.nimrodtechs.ipcrsock.server.DefaultRsocketServer;
import com.nimrodtechs.ipcrsock.subscriber.SubscriberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class RSocketModeLogger {

    private static final Logger log = LoggerFactory.getLogger(RSocketModeLogger.class);
    private final Environment env;
    private final ApplicationContext ctx;

    public RSocketModeLogger(Environment env, ApplicationContext ctx) {
        this.env = env;
        this.ctx = ctx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logActiveMode() {

        // --- RMI (request/response) ---
        boolean serverEnabled = !ctx.getBeansOfType(DefaultRsocketServer.class).isEmpty();
        boolean clientEnabled = !ctx.getBeansOfType(RemoteServerService.class).isEmpty();

        // --- Pub/Sub ---
        boolean publisherEnabled = !ctx.getBeansOfType(PublisherSocketImpl.class).isEmpty();
        boolean subscriberEnabled = !ctx.getBeansOfType(SubscriberService.class).isEmpty();

        // --- RMI Summary ---
        if (clientEnabled && serverEnabled) {
            log.info("Nimrod IPC RMI Mode: DUAL (Client + Server)");
        } else if (serverEnabled) {
            log.info("Nimrod IPC RMI Mode: SERVER-ONLY");
        } else if (clientEnabled) {
            log.info("Nimrod IPC RMI Mode: CLIENT-ONLY");
        } else {
            log.warn("Nimrod IPC RMI Mode: NONE");
        }

        // --- Pub/Sub Summary ---
        if (publisherEnabled && subscriberEnabled) {
            log.info("Nimrod IPC Pub/Sub Mode: DUAL (Publisher + Subscriber)");
        } else if (publisherEnabled) {
            log.info("Nimrod IPC Pub/Sub Mode: PUBLISHER-ONLY");
        } else if (subscriberEnabled) {
            log.info("Nimrod IPC Pub/Sub Mode: SUBSCRIBER-ONLY");
        } else {
            log.info("Nimrod IPC Pub/Sub Mode: NONE");
        }
    }
}