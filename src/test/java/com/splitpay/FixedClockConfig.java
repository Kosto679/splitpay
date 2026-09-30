package com.splitpay;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class FixedClockConfig {

    public static final ZoneId ZONE = ZoneId.of("Europe/Athens");
    public static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 18, 12, 0);

    @Bean
    @Primary
    Clock fixedClock() {
        return Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
    }
}
