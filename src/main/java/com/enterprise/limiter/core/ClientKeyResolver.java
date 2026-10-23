package com.enterprise.limiter.core;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Extracts and sanitizes unique client rate-limiting keys from HTTP request attributes.
 *
 * <p>Precedence order:
 * <ol>
 *   <li>{@code X-API-Key} header &rarr; {@code api_key:<key>}</li>
 *   <li>{@code X-Tenant-ID} header &rarr; {@code tenant:<tenant>}</li>
 *   <li>{@code X-Forwarded-For} header (first client IP) &rarr; {@code ip:<ip>}</li>
 *   <li>Servlet {@code getRemoteAddr()} &rarr; {@code ip:<ip>}</li>
 * </ol>
 */
@Component
public class ClientKeyResolver {

    public static final String HEADER_API_KEY = "X-API-Key";
    public static final String HEADER_TENANT_ID = "X-Tenant-ID";
    public static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";

    public static final String PREFIX_API_KEY = "api_key:";
    public static final String PREFIX_TENANT = "tenant:";
    public static final String PREFIX_IP = "ip:";
    public static final String UNKNOWN_IP = "ip:unknown";

    /**
     * Resolves the client rate-limiting key from an incoming HttpServletRequest.
     *
     * @param request HTTP request
     * @return non-null resolved client key
     */
    public String resolveKey(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN_IP;
        }

        String apiKey = request.getHeader(HEADER_API_KEY);
        String tenantId = request.getHeader(HEADER_TENANT_ID);
        String forwardedFor = request.getHeader(HEADER_FORWARDED_FOR);
        String remoteAddr = request.getRemoteAddr();

        return resolveKey(apiKey, tenantId, forwardedFor, remoteAddr);
    }

    /**
     * Resolves the client key from individual header and address components.
     *
     * @param apiKey API key header value
     * @param tenantId Tenant ID header value
     * @param forwardedFor X-Forwarded-For header value
     * @param remoteAddr Direct socket remote IP address
     * @return non-null resolved client key
     */
    public String resolveKey(String apiKey, String tenantId, String forwardedFor, String remoteAddr) {
        // 1. Highest priority: X-API-Key
        if (isNotBlank(apiKey)) {
            return PREFIX_API_KEY + apiKey.trim();
        }

        // 2. Second priority: X-Tenant-ID
        if (isNotBlank(tenantId)) {
            return PREFIX_TENANT + tenantId.trim();
        }

        // 3. Third priority: X-Forwarded-For (client is first element before proxy hops)
        if (isNotBlank(forwardedFor)) {
            String clientIp = extractFirstIp(forwardedFor);
            if (isNotBlank(clientIp)) {
                return PREFIX_IP + clientIp;
            }
        }

        // 4. Fourth priority: Remote IP Address
        if (isNotBlank(remoteAddr)) {
            return PREFIX_IP + remoteAddr.trim();
        }

        return UNKNOWN_IP;
    }

    private String extractFirstIp(String forwardedFor) {
        int commaIndex = forwardedFor.indexOf(',');
        String first = (commaIndex != -1) ? forwardedFor.substring(0, commaIndex) : forwardedFor;
        return first.trim();
    }

    private boolean isNotBlank(String str) {
        return str != null && !str.isBlank();
    }
}
