package com.enterprise.limiter;

import com.enterprise.limiter.core.NanoClock;
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
    public NanoClock nanoClock() {
        return NanoClock.system();
    }

    @Bean
    public CommandLineRunner startupBanner(Environment env) {
        return args -> {
            String port = env.getProperty("server.port", "8080");
            boolean virtualThreadsEnabled = Boolean.parseBoolean(
                    env.getProperty("spring.threads.virtual.enabled", "false"));
            String defaultCapacity = env.getProperty("rate-limiter.default-capacity", "50");
            String defaultRefill = env.getProperty("rate-limiter.default-refill-rate", "10");
            String ttl = env.getProperty("rate-limiter.bucket-ttl", "10m");
            String upstream = env.getProperty("proxy.target-url", "http://localhost:8081");

            log.info("==================================================================");
            log.info("  HIGH-THROUGHPUT API RATE LIMITER & REVERSE PROXY");
            log.info("==================================================================");
            log.info("  Active Listen Port      : {}", port);
            log.info("  Upstream Target URL     : {}", upstream);
            log.info("  Virtual Threading (Loom): {}", virtualThreadsEnabled ? "ENABLED (Java 21 Project Loom)" : "DISABLED");
            log.info("  Prometheus Metrics URI  : /actuator/prometheus");
            log.info("  Default Rate Limit Rule : Capacity: {} | Refill: {} tokens/sec", defaultCapacity, defaultRefill);
            log.info("  Bucket Memory TTL       : {}", ttl);
            log.info("==================================================================");
        };
    }
}
