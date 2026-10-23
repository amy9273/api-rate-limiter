package com.enterprise.limiter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("Spring Boot Application & Virtual Threads Integration Test")
class RateLimiterApplicationTests {

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("Application context loads with Virtual Threads enabled")
    void contextLoads() {
        String virtualThreads = environment.getProperty("spring.threads.virtual.enabled");
        assertThat(virtualThreads).isEqualTo("true");
    }
}
