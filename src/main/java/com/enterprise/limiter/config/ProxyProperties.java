package com.enterprise.limiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration properties for reverse proxy forwarding to upstream services.
 */
@Component
@ConfigurationProperties(prefix = "proxy")
public class ProxyProperties {

    /**
     * Upstream target base URL (e.g. http://localhost:8081).
     */
    private String targetUrl = "http://localhost:8081";

    /**
     * Connection timeout to upstream server.
     */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * Read/response timeout from upstream server.
     */
    private Duration readTimeout = Duration.ofSeconds(10);

    public ProxyProperties() {
    }

    public ProxyProperties(String targetUrl, Duration connectTimeout, Duration readTimeout) {
        this.targetUrl = targetUrl;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
