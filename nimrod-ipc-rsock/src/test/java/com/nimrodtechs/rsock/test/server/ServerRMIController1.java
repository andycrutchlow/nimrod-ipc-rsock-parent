package com.nimrodtechs.rsock.test.server;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

@Controller
public class ServerRMIController1 {
    private static final Logger log = LoggerFactory.getLogger(ServerRMIController1.class);
    @PostConstruct
    void init() {
        System.out.println("HERE ServerRMIController1");
    }

    int i = 100;
    @MessageMapping("getMarketData1")
    public Mono<String> getMarketData1(Object[] params) {
        StringBuffer sb = new StringBuffer();
        for(Object o : params) {
            if(sb.length() > 0){
                sb.append(",");
            }
            sb.append(o);
        }
        log.info("getMarketData1 "+sb.toString());
        return Mono.just("getMarketData1 response params="+sb.toString()+" "+i++);
    }

    @MessageMapping("postMarketData1")
    public Mono<Void> postMarketData1(Object[] params) {
        log.info("postMarketData1 "+params);
        return Mono.empty();
    }
}
