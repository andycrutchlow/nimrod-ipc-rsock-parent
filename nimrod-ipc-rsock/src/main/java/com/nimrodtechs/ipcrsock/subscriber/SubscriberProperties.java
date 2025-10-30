package com.nimrodtechs.ipcrsock.subscriber;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@EnableConfigurationProperties(SubscriberProperties.class)
@ConfigurationProperties(prefix = "nimrod.rsock.subscriber")
public class SubscriberProperties {

    private List<String> setup;

    public List<String> getSetup() {
        return setup;
    }

    public void setSetup(List<String> setup) {
        this.setup = setup;
    }
}