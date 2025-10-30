package com.nimrodtechs.ipcrsock.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@EnableConfigurationProperties(RemoteServerProperties.class)
@ConfigurationProperties(prefix = "nimrod.rsock.server")
public class RemoteServerProperties {

    private List<String> setup;

    public List<String> getSetup() {
        return setup;
    }

    public void setSetup(List<String> setup) {
        this.setup = setup;
    }
}