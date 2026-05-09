package com.porter.platform.producer.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.porter.platform.producer.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-KEY";

    private final Set<String> validApiKeys;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(
            @Value("${app.security.api-keys}") Set<String> validApiKeys,
            ObjectMapper objectMapper) {
        this.validApiKeys = validApiKeys;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // SSE stats stream is public (read-only). OPTIONS preflight must pass for CORS.
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || "/api/v1/stats/stream".equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader(API_KEY_HEADER);

        if (apiKey == null || apiKey.isBlank()) {
            writeError(response, 401, "Unauthorized", "Missing X-API-KEY header", request.getRequestURI());
            return;
        }

        if (!validApiKeys.contains(apiKey)) {
            writeError(response, 403, "Forbidden", "Invalid API key", request.getRequestURI());
            return;
        }

        // Attach API key to request attribute for rate limiter downstream
        request.setAttribute("apiKey", apiKey);
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, int status, String error,
                            String message, String path) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(status, error, message, path));
    }
}
