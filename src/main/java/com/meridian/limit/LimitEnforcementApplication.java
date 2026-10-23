package com.meridian.limit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the cross-system daily transfer limit enforcement service.
 *
 * <p>Implements the design described in
 * {@code docs/aws-modernization-cross-system-limits.md}: a running total that is
 * derived (never stored), a reserve -> commit/release lifecycle, gap-lock based
 * serialization of same-customer transfers, and a reconciler that never guesses
 * about money.
 */
@SpringBootApplication
@EnableScheduling
public class LimitEnforcementApplication {

    public static void main(String[] args) {
        SpringApplication.run(LimitEnforcementApplication.class, args);
    }
}
