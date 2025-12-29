package com.traderecon.tradepublisher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class TradePublisherApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradePublisherApplication.class, args);
    }
}