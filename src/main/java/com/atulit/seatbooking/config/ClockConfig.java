package com.atulit.seatbooking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Time is injected rather than read from {@code Instant.now()} so tests can move it.
 * Without this, testing hold expiry means sleeping, and sleeping tests are slow tests.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
