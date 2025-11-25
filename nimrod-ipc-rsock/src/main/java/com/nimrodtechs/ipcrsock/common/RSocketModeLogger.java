package com.nimrodtechs.ipcrsock.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class RSocketModeLogger {

    private static final Logger log = LoggerFactory.getLogger(RSocketModeLogger.class);
    private final Environment env;

    public RSocketModeLogger(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logActiveMode() {
        List<String> active = Arrays.asList(env.getActiveProfiles());
        if (active.contains("manualrsockserver")) {
            log.info("Nimrod IPC RSocket running in **MANUAL RMI SERVER** mode (dynamic port discovery).");
        } else if (active.contains("nimrod-rmi-client") && active.contains("nimrod-rmi-server")) {
            log.info("Nimrod IPC RSocket running in **DUAL MODE** — both RMI Server and Client components active (static port config).");
        } else if (active.contains("nimrod-rmi-server")) {
            log.info("Nimrod IPC RSocket running in **RMI SERVER-ONLY** mode (no client components).");
        } else if (active.contains("nimrod-rmi-client")) {
            log.info("Nimrod IPC RSocket running in **CLIENT-ONLY** mode (no server listener).");
        } else {
            log.info("Nimrod IPC RSocket running in **DEFAULT (DUAL)** mode — both RMI Server and Client components active (static port config).");
        }    }
}