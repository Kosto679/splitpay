package com.splitpay;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SplitpayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SplitpayApplication.class, args);
    }

    @Bean
    Clock clock(SplitpayProperties properties) {
        return Clock.system(ZoneId.of(properties.timezone()));
    }
}
