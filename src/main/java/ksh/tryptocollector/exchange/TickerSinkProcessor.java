package ksh.tryptocollector.exchange;

import io.micrometer.core.annotation.Timed;
import ksh.tryptocollector.candle.CandleBuffer;
import ksh.tryptocollector.model.NormalizedTicker;
import ksh.tryptocollector.rabbitmq.TickerEventPublisher;
import ksh.tryptocollector.redis.TickerRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TickerSinkProcessor {
    private final TickerRedisRepository tickerRedisRepository;
    private final TickerEventPublisher tickerEventPublisher;
    private final CandleBuffer candleBuffer;

    @Timed(value = "ticker.latency")
    public void process(NormalizedTicker ticker) {
        try {
            candleBuffer.update(ticker);
        } catch (Exception e) {
            log.debug("캔들 버퍼 갱신 실패: {}", e.getMessage());
        }
        try {
            tickerRedisRepository.save(ticker);
        } catch (Exception e) {
            log.error("Redis 저장 실패: {}/{}", ticker.exchange(), ticker.base(), e);
        }
        try {
            tickerEventPublisher.publish(ticker);
        } catch (Exception e) {
            log.error("RabbitMQ 발행 실패: {}/{}", ticker.exchange(), ticker.base(), e);
        }
    }
}
