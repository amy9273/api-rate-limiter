package com.enterprise.limiter.core;

import com.enterprise.limiter.config.ProxyProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Extracts, validates, and sanitizes unique client rate-limiting keys from HTTP request attributes.
 *
 * <p>Precedence order:
 * <ol>
 *   <li>{@code X-API-Key} header &rarr; {@code api_key:<sanitized_key>} (or SHA-256 hash if &gt; 256 chars)</li>
 *   <li>{@code X-Tenant-ID} header &rarr; {@code tenant:<sanitized_tenant>} (or SHA-256 hash if &gt; 256 chars)</li>
 *   <li>{@code X-Forwarded-For} header &rarr; {@code ip:<ip>} (<b>only</b> when direct peer is a trusted proxy)</li>
 *   <li>Servlet {@code getRemoteAddr()} &rarr; {@code ip:<ip>}</li>
 * </ol>
 *
 * <p>Security features:
 * <ul>
 *   <li>Prevents IP spoofing (LD-02) by requiring the connecting socket peer to match trusted CIDRs.</li>
 *   <li>Evaluates multi-hop {@code X-Forwarded-For} right-to-left, identifying the true client.</li>
 *   <li>Strips non-printable/control characters to protect against CWE-117 log and header injection.</li>
 *   <li>Enforces length limits (&le; 256 chars) with deterministic SHA-256 hashing to bound memory.</li>
 *   <li>Strips port notations and performs strict IP literal checks without blocking DNS lookups.</li>
 * </ul>
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

    public static final int MAX_KEY_LENGTH = 256;

    private final ProxyProperties proxyProperties;
    private volatile List<CidrRange> cachedRanges;
    private volatile List<String> lastKnownTrustedList;

    public ClientKeyResolver() {
        this(new ProxyProperties());
    }

    @Autowired
    public ClientKeyResolver(ProxyProperties proxyProperties) {
        this.proxyProperties = proxyProperties != null ? proxyProperties : new ProxyProperties();
    }

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
        String cleanApiKey = sanitizeKey(apiKey);
        if (cleanApiKey != null) {
            return PREFIX_API_KEY + cleanApiKey;
        }

        // 2. Second priority: X-Tenant-ID
        String cleanTenantId = sanitizeKey(tenantId);
        if (cleanTenantId != null) {
            return PREFIX_TENANT + cleanTenantId;
        }

        // 3. Third priority: Client IP
        String cleanRemoteAddr = stripPort(remoteAddr);
        if (cleanRemoteAddr != null && !isValidIpLiteral(cleanRemoteAddr)) {
            cleanRemoteAddr = null;
        }

        // Only inspect X-Forwarded-For if direct socket remoteAddr is verified as a trusted proxy
        if (cleanRemoteAddr != null && isTrustedProxy(cleanRemoteAddr)) {
            if (isNotBlank(forwardedFor)) {
                String clientIp = resolveForwardedClientIp(forwardedFor);
                if (clientIp != null) {
                    return PREFIX_IP + clientIp;
                }
            }
        }

        // 4. Remote IP Address (untrusted direct peer or fallback)
        if (cleanRemoteAddr != null) {
            return PREFIX_IP + cleanRemoteAddr;
        }

        return UNKNOWN_IP;
    }

    /**
     * Evaluates multi-hop X-Forwarded-For headers from right to left (from the most recent proxy
     * towards the client). Returns the first untrusted IP encountered in the chain.
     * If all hops are trusted, returns the leftmost hop.
     *
     * @param forwardedFor raw X-Forwarded-For header value
     * @return genuine client IP or null if no valid IP literal found
     */
    private String resolveForwardedClientIp(String forwardedFor) {
        String[] hops = forwardedFor.split(",");
        List<String> validHops = new ArrayList<>();
        for (String hop : hops) {
            String clean = stripPort(hop);
            if (clean != null && isValidIpLiteral(clean)) {
                validHops.add(clean);
            }
        }
        if (validHops.isEmpty()) {
            return null;
        }

        // Traverse right-to-left: the first untrusted IP from the right is the genuine client
        for (int i = validHops.size() - 1; i >= 0; i--) {
            String ip = validHops.get(i);
            if (!isTrustedProxy(ip)) {
                return ip;
            }
        }

        // If all hops in the chain are trusted, the leftmost hop is the origin client
        return validHops.get(0);
    }

    /**
     * Checks whether an IP address matches the configured trusted proxies or CIDR subnets.
     *
     * @param ip IP literal string
     * @return true if trusted
     */
    public boolean isTrustedProxy(String ip) {
        if (ip == null || !isValidIpLiteral(ip)) {
            return false;
        }
        List<CidrRange> ranges = getCompiledRanges();
        try {
            InetAddress addr = InetAddress.getByName(ip);
            for (CidrRange range : ranges) {
                if (range.matches(addr)) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private List<CidrRange> getCompiledRanges() {
        List<String> currentList = proxyProperties != null ? proxyProperties.getTrustedProxies() : null;
        if (currentList == null) {
            return Collections.emptyList();
        }
        if (currentList == lastKnownTrustedList && cachedRanges != null) {
            return cachedRanges;
        }
        synchronized (this) {
            if (currentList == lastKnownTrustedList && cachedRanges != null) {
                return cachedRanges;
            }
            List<CidrRange> list = new ArrayList<>();
            for (String cidr : currentList) {
                if (cidr != null && !cidr.isBlank()) {
                    try {
                        list.add(new CidrRange(cidr.trim()));
                    } catch (Exception ignored) {
                        // Ignore malformed CIDR configuration
                    }
                }
            }
            cachedRanges = Collections.unmodifiableList(list);
            lastKnownTrustedList = currentList;
            return cachedRanges;
        }
    }

    /**
     * Sanitizes raw key string by removing control characters, trimming whitespace,
     * and hashing with SHA-256 if exceeding {@link #MAX_KEY_LENGTH}.
     *
     * @param raw input key
     * @return sanitized string or null if empty
     */
    public static String sanitizeKey(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        String clean = sb.toString().trim();
        if (clean.isBlank()) {
            return null;
        }
        if (clean.length() > MAX_KEY_LENGTH) {
            return "sha256:" + sha256Hex(clean);
        }
        return clean;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Strips port notation from IPv4 (e.g. 192.168.1.1:8080) and bracketed IPv6 (e.g. [::1]:8080).
     *
     * @param ip input IP string
     * @return IP literal without port
     */
    public static String stripPort(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        String s = ip.trim();
        if (s.startsWith("[") && s.contains("]")) {
            int closeBracket = s.indexOf(']');
            return s.substring(1, closeBracket);
        }
        int firstColon = s.indexOf(':');
        int lastColon = s.lastIndexOf(':');
        // If exactly one colon, it is IPv4:port
        if (firstColon != -1 && firstColon == lastColon) {
            return s.substring(0, firstColon);
        }
        return s;
    }

    /**
     * Checks if a string contains characters consistent with an IPv4 or IPv6 literal,
     * guaranteeing no DNS resolution will be attempted by the system resolver.
     *
     * @param s IP string candidate
     * @return true if candidate contains only valid IP literal characters
     */
    public static boolean isValidIpLiteral(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        boolean hasDot = false;
        boolean hasColon = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                continue;
            }
            if (c == '.') {
                hasDot = true;
                continue;
            }
            if (c == ':') {
                hasColon = true;
                continue;
            }
            if ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                continue;
            }
            return false;
        }
        return hasDot || hasColon;
    }

    private static boolean isNotBlank(String str) {
        return str != null && !str.isBlank();
    }

    /**
     * Precompiled CIDR subnet matcher supporting IPv4, IPv6, and wildcard masks.
     */
    public static class CidrRange {
        private final byte[] networkBytes;
        private final int prefixLength;
        private final boolean matchAll;

        public CidrRange(String cidr) throws Exception {
            if ("*".equals(cidr)) {
                this.networkBytes = null;
                this.prefixLength = 0;
                this.matchAll = true;
                return;
            }
            this.matchAll = false;
            String ipPart;
            int prefix;
            int slash = cidr.indexOf('/');
            if (slash != -1) {
                ipPart = cidr.substring(0, slash).trim();
                prefix = Integer.parseInt(cidr.substring(slash + 1).trim());
            } else {
                ipPart = cidr.trim();
                prefix = -1;
            }
            byte[] raw = normalize(InetAddress.getByName(ipPart).getAddress());
            boolean isIpv6 = raw.length == 16;
            this.prefixLength = prefix >= 0 ? prefix : (isIpv6 ? 128 : 32);
            this.networkBytes = raw;
        }

        public boolean matches(InetAddress address) {
            if (matchAll) {
                return true;
            }
            if (address == null || networkBytes == null) {
                return false;
            }
            byte[] ipBytes = normalize(address.getAddress());
            if (ipBytes.length != networkBytes.length) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (ipBytes[i] != networkBytes[i]) {
                    return false;
                }
            }
            int remBits = prefixLength % 8;
            if (remBits > 0) {
                int mask = (~((1 << (8 - remBits)) - 1)) & 0xFF;
                if ((ipBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        }

        private static byte[] normalize(byte[] bytes) {
            if (bytes.length == 16) {
                boolean isMapped = true;
                for (int i = 0; i < 10; i++) {
                    if (bytes[i] != 0) {
                        isMapped = false;
                        break;
                    }
                }
                if (isMapped && (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF) {
                    return Arrays.copyOfRange(bytes, 12, 16);
                }
            }
            return bytes;
        }
    }
}
