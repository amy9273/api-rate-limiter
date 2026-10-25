package com.enterprise.limiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * Trusted proxy IP literals or CIDR ranges allowed to supply X-Forwarded-For headers.
     * Defaults to loopback and RFC 1918 private subnets.
     */
    private List<String> trustedProxies = new ArrayList<>(List.of(
            "127.0.0.1",
            "::1",
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16"
    ));

    public ProxyProperties() {
    }

    public ProxyProperties(String targetUrl, Duration connectTimeout, Duration readTimeout) {
        this(targetUrl, connectTimeout, readTimeout, new ArrayList<>(List.of(
                "127.0.0.1",
                "::1",
                "10.0.0.0/8",
                "172.16.0.0/12",
                "192.168.0.0/16"
        )));
    }

    public ProxyProperties(String targetUrl, Duration connectTimeout, Duration readTimeout, List<String> trustedProxies) {
        this.targetUrl = targetUrl;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.trustedProxies = trustedProxies != null ? new ArrayList<>(trustedProxies) : new ArrayList<>();
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

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies != null ? new ArrayList<>(trustedProxies) : new ArrayList<>();
    }
}
