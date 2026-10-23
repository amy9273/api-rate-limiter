package com.enterprise.limiter.proxy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Controller mapping non-actuator requests to the {@link ReverseProxyService}.
 */
@RestController
public class ReverseProxyController {

    private final ReverseProxyService proxyService;

    @Autowired
    public ReverseProxyController(ReverseProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @RequestMapping(value = "/**")
    public void proxyAll(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/actuator")) {
            return;
        }
        proxyService.proxyRequest(request, response);
    }
}
