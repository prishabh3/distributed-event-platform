package com.porter.platform.producer.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.porter.platform.producer.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class RateLimiterFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterFilter.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> tokenBucketScript;
    private final int rateLimit;

    public RateLimiterFilter(
            RedisTemplate<String, String> redisTemplate,
            DefaultRedisScript<Long> tokenBucketScript,
            @Value("${app.rate-limit.requests-per-second:100}") int rateLimit,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
        this.rateLimit = rateLimit;
        this.objectMapper = objectMapper;
    }

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String apiKey = (String) request.getAttribute("apiKey");
        if (apiKey == null) {
            // No API key means the previous filter already rejected — skip
            filterChain.doFilter(request, response);
            return;
        }

        String bucketKey = "ratelimit:" + apiKey;
        long now = System.currentTimeMillis();

        try {
            Long result = redisTemplate.execute(
                    tokenBucketScript,
                    List.of(bucketKey),
                    String.valueOf(rateLimit),
                    String.valueOf(rateLimit),
                    String.valueOf(now)
            );

            if (result == null || result == 0L) {
                response.setStatus(429);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimit));
                response.setHeader("Retry-After", "1");
                objectMapper.writeValue(response.getWriter(),
                        ErrorResponse.of(429, "Too Many Requests",
                                "Rate limit of " + rateLimit + " req/s exceeded",
                                request.getRequestURI()));
                return;
            }
        } catch (Exception e) {
            // Redis failure — fail open to avoid cascading outage
            log.warn("Rate limiter Redis call failed, failing open: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
