package com.nimrodtechs.ipcrsock.client;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
@Component
@ConfigurationProperties(prefix = "nimrod.rsock")
public class RemoteServerProperties {

    private Map<String, RemoteServerInfo> clientSide = new LinkedHashMap<>();

    public Map<String, RemoteServerInfo> getClientSide() {
        return clientSide;
    }

    public void setClientSide(Map<String, RemoteServerInfo> clientSide) {
        this.clientSide = clientSide;
    }

    @PostConstruct
    void applyNames() {
        clientSide.forEach((name, info) -> info.setName(name));
    }
}
