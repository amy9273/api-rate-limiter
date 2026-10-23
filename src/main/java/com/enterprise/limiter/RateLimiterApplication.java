package com.enterprise.limiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class RateLimiterApplication {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(RateLimiterApplication.class, args);
    }

    @Bean
    public CommandLineRunner startupBanner(Environment env) {
        return args -> {
            String port = env.getProperty("server.port", "8080");
            boolean virtualThreadsEnabled = Boolean.parseBoolean(
                    env.getProperty("spring.threads.virtual.enabled", "false"));

            log.info("==================================================================");
            log.info("  HIGH-THROUGHPUT API RATE LIMITER & REVERSE PROXY");
            log.info("==================================================================");
            log.info("  Active Listen Port      : {}", port);
            log.info("  Virtual Threading (Loom): {}", virtualThreadsEnabled ? "ENABLED (Java 21 Project Loom)" : "DISABLED");
            log.info("  Prometheus Metrics URI  : /actuator/prometheus");
            log.info("  Default Rate Limit Rule : Capacity: 50 | Refill: 10 tokens/sec");
            log.info("==================================================================");
        };
    }
}
