package ksh.tryptocollector.exchange;

import ksh.tryptocollector.candle.CandleBuffer;
import ksh.tryptocollector.model.NormalizedTicker;
import ksh.tryptocollector.rabbitmq.TickerEventPublisher;
import ksh.tryptocollector.redis.TickerRedisRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TickerSinkProcessorTest {

    @Mock
    private TickerRedisRepository tickerRedisRepository;

    @Mock
    private TickerEventPublisher tickerEventPublisher;

    @Mock
    private CandleBuffer candleBuffer;

    @InjectMocks
    private TickerSinkProcessor tickerSinkProcessor;

    @Test
    @DisplayName("CandleBuffer가 예외를 던져도 Redis 저장과 RabbitMQ 발행은 정상 수행된다")
    void givenCandleBufferThrows_whenProcess_thenRedisAndRabbitMqStillProceed() {
        // given
        NormalizedTicker ticker = new NormalizedTicker(
                "upbit", "BTC", "KRW", "BTC/KRW",
                new BigDecimal("50000000"), BigDecimal.ZERO, BigDecimal.ZERO, System.currentTimeMillis()
        );
        willThrow(new RuntimeException("buffer error")).given(candleBuffer).update(any());

        // when
        tickerSinkProcessor.process(ticker);

        // then
        verify(tickerRedisRepository).save(ticker);
        verify(tickerEventPublisher).publish(ticker);
    }
}
