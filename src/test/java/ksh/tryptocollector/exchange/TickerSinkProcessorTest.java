package ksh.tryptocollector.exchange;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import ksh.tryptocollector.model.NormalizedTicker;
import ksh.tryptocollector.rabbitmq.EngineInboxPublisher;
import ksh.tryptocollector.rabbitmq.TickerEventPublisher;
import ksh.tryptocollector.redis.TickerRedisRepository;
import ksh.tryptocollector.tick.TickRawWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TickerSinkProcessorTest {

    @Mock private TickerRedisRepository tickerRedisRepository;
    @Mock private TickerEventPublisher tickerEventPublisher;
    @Mock private EngineInboxPublisher engineInboxPublisher;
    @Mock private TickRawWriter tickRawWriter;

    private TickerSinkProcessor tickerSinkProcessor;

    @BeforeEach
    void setUp() {
        CircuitBreaker circuitBreaker = CircuitBreakerRegistry.ofDefaults().circuitBreaker("redis");
        tickerSinkProcessor = new TickerSinkProcessor(
                tickerRedisRepository, tickerEventPublisher, engineInboxPublisher,
                tickRawWriter, circuitBreaker);
    }

    @Test
    @DisplayName("TickRawWriter 예외가 나도 Redis/RabbitMQ/engine.inbox 발행은 계속된다")
    void givenTickRawWriterThrows_whenProcess_thenOtherSinksProceed() {
        NormalizedTicker ticker = new NormalizedTicker(
                "upbit", "BTC", "KRW", "BTC/KRW",
                new BigDecimal("50000000"), BigDecimal.ZERO, BigDecimal.ZERO, System.currentTimeMillis()
        );
        willThrow(new RuntimeException("write error")).given(tickRawWriter).write(any());

        tickerSinkProcessor.process(ticker);

        verify(tickerRedisRepository).save(ticker);
        verify(tickerEventPublisher).publish(ticker);
        verify(engineInboxPublisher).publish(ticker);
    }
}
