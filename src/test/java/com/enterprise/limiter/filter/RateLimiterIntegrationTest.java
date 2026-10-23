package com.enterprise.limiter.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(RateLimiterIntegrationTest.DummyApiController.class)
@DisplayName("Rate Limiter HTTP Pipeline End-to-End Integration Test")
class RateLimiterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @RestController
    static class DummyApiController {
        @GetMapping("/api/resource")
        public Map<String, String> getResource() {
            return Map.of("message", "success");
        }
    }

    @Test
    @DisplayName("HTTP GET /api/resource receives RFC 6585 headers and succeeds")
    void shouldPassThroughPipelineWithHeaders() throws Exception {
        mockMvc.perform(get("/api/resource")
                        .header("X-API-Key", "sk_client_integ_test")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "50"))
                .andExpect(header().exists("X-RateLimit-Remaining"))
                .andExpect(header().exists("X-RateLimit-Reset"))
                .andExpect(jsonPath("$.message", is("success")));
    }

    @Test
    @DisplayName("Actuator /actuator/health is accessible without rate-limiting headers")
    void actuatorHealthShouldBypassRateLimiter() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-RateLimit-Limit"));
    }

    @Test
    @DisplayName("Actuator /actuator/prometheus is accessible and exposes metrics")
    void actuatorPrometheusShouldExposeLimiterMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_")));
    }
}
