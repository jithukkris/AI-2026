package com.healpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * HealPay AI - a self-healing payment balancing system.
 *
 * Detect -> Diagnose (AI) -> Decide (policy) -> Act (healing) -> Verify (re-reconcile).
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class HealPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(HealPayApplication.class, args);
    }
}
