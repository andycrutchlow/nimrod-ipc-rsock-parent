package com.nimrodtechs.rsock.test.client;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.ComponentScan;

import java.awt.*;

@SpringBootApplication
//@ComponentScan(basePackages = {"com.nimrodtechs.rsock.subscriber","com.nimrodtechs.rsock.client","com.nimrodtechs.rsock.common","com.nimrodtechs.rsock.test.client"})
@ComponentScan(basePackages = {"com.nimrodtechs.ipcrsock","com.nimrodtechs.rsock.test.client"})

public class ClientAndSubscriberApplication {
    @Autowired
    ClientAndSubscriberGui clientAndSubscriberGui;

    public static void main(String[] args) {
        new SpringApplicationBuilder()
                .main(ClientAndSubscriberApplication.class)
                .sources(ClientAndSubscriberApplication.class)
                .profiles("clientAndSubscriber")
                .run(args);

    }
    @PostConstruct
    void init() {
        EventQueue.invokeLater(new Runnable() {
            public void run() {
                clientAndSubscriberGui.pack();
                clientAndSubscriberGui.setVisible(true);
            }
        });

    }
}
