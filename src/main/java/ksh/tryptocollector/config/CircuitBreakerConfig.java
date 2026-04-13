package ksh.tryptocollector.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CircuitBreakerConfig {

    private static final int SLIDING_WINDOW_SIZE = 5;
    private static final float FAILURE_RATE_THRESHOLD = 60;
    private static final int WAIT_DURATION_OPEN_SECONDS = 10;
    private static final int PERMITTED_CALLS_HALF_OPEN = 2;

    @Bean
    public CircuitBreaker redisCircuitBreaker(MeterRegistry meterRegistry) {
        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig config =
                io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                        .slidingWindowSize(SLIDING_WINDOW_SIZE)
                        .failureRateThreshold(FAILURE_RATE_THRESHOLD)
                        .waitDurationInOpenState(Duration.ofSeconds(WAIT_DURATION_OPEN_SECONDS))
                        .permittedNumberOfCallsInHalfOpenState(PERMITTED_CALLS_HALF_OPEN)
                        .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        CircuitBreaker circuitBreaker = registry.circuitBreaker("redis");

        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry)
                .bindTo(meterRegistry);

        return circuitBreaker;
    }
}
