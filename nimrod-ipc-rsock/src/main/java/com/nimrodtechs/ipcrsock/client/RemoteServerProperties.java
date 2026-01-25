package com.nimrodtechs.ipcrsock.client;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
//@Profile({"nimrod-rmi-client","default"})
//@EnableConfigurationProperties(RemoteServerProperties.class)
@ConfigurationProperties(prefix = "nimrod.rsock.server")
public class RemoteServerProperties {
    private Map<String, RemoteServerInfo> servers = new LinkedHashMap<>();

    public RemoteServerProperties() {
        System.out.println("HERE");
    }

    public Map<String, RemoteServerInfo> getServers() {
        return servers;
    }

    public void setServers(Map<String, RemoteServerInfo> servers) {
        this.servers = servers;
    }

    @PostConstruct
    void applyNames() {
        servers.forEach((name, info) -> info.setName(name));
    }
}
