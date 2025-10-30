package com.nimrodtechs.ipcrsock.client;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = {"com.nimrodtechs.ipcrsock.common","com.nimrodtechs.ipcrsock.serialization","com.nimrodtechs.ipcrsock.actuator"})
public class ClientConfig {
}
