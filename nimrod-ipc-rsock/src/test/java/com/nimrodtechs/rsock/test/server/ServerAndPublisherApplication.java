package com.nimrodtechs.rsock.test.server;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.ComponentScan;

import java.awt.*;

@SpringBootApplication
//@ComponentScan(basePackages = {"com.nimrodtechs.rsock.publisher","com.nimrodtechs.rsock.test.server"})
@ComponentScan(basePackages = {"com.nimrodtechs.ipcrsock","com.nimrodtechs.rsock.test.server"})
public class ServerAndPublisherApplication {
    @Autowired
    ServerAndPublisherGui serverAndPublisherGui;

    public static void main(String[] args) {
//        String[] profiles = args.length>0 ? new String[2] : new String[1];
//        profiles[0] = "serverAndPublisher";
//        if(args.length > 0) {
//            profiles[1] = args[0];
//        }
        new SpringApplicationBuilder()
                .main(ServerAndPublisherApplication.class)
                .sources(ServerAndPublisherApplication.class)
//                .profiles(profiles)
                .run(args);

    }
    @PostConstruct
    void init() {
        EventQueue.invokeLater(new Runnable() {
            public void run() {
                serverAndPublisherGui.pack();
                serverAndPublisherGui.setVisible(true);
            }
        });

    }
}
