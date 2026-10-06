package com.github.lechandonga.incidentremediationorchestratorjava.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 使用系统 UTC 时钟；测试可通过 TestConfiguration 替换为可控时钟。 */
@Configuration
public class AppConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
