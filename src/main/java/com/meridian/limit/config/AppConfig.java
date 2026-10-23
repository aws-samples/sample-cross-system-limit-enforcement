package com.meridian.limit.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/**
 * Application wiring: enables typed config properties and exposes a
 * {@link RestClient} used by the legacy adapter and a {@link Clock} used for
 * deterministic time handling.
 */
@Configuration
@EnableConfigurationProperties(LimitEnforcementProperties.class)
public class AppConfig {

    @Bean
    RestClient restClient(RestClient.Builder builder) {
        return builder.build();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
