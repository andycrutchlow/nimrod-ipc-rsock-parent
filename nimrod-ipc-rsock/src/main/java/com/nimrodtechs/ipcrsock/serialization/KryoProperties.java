package com.nimrodtechs.ipcrsock.serialization;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "nimrod.rsock.kryo")
public class KryoProperties {
    private List<Class<?>> additionalClasses = new ArrayList<>();

    public List<Class<?>> getAdditionalClasses() {
        return additionalClasses;
    }

    public void setAdditionalClasses(List<Class<?>> additionalClasses) {
        this.additionalClasses = additionalClasses;
    }
}