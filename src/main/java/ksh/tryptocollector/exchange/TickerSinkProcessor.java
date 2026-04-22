package ksh.tryptocollector.exchange;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import ksh.tryptocollector.model.NormalizedTicker;
import ksh.tryptocollector.rabbitmq.EngineInboxPublisher;
import ksh.tryptocollector.rabbitmq.TickerEventPublisher;
import ksh.tryptocollector.redis.TickerRedisRepository;
import ksh.tryptocollector.tick.TickRawWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TickerSinkProcessor {
    private final TickerRedisRepository tickerRedisRepository;
    private final TickerEventPublisher tickerEventPublisher;
    private final EngineInboxPublisher engineInboxPublisher;
    private final TickRawWriter tickRawWriter;
    private final CircuitBreaker redisCircuitBreaker;

    public void process(NormalizedTicker ticker) {
        writeRawTick(ticker);
        saveToRedis(ticker);
        publishEvent(ticker);
        publishToEngine(ticker);
    }

    private void writeRawTick(NormalizedTicker ticker) {
        try {
            tickRawWriter.write(ticker);
        } catch (Exception e) {
            log.debug("InfluxDB raw tick 저장 실패: {}", e.getMessage());
        }
    }

    private void saveToRedis(NormalizedTicker ticker) {
        if (redisCircuitBreaker.getState() == CircuitBreaker.State.OPEN) return;
        try {
            tickerRedisRepository.save(ticker);
        } catch (Exception e) {
            log.error("Redis 저장 실패: {}/{}", ticker.exchange(), ticker.base(), e);
        }
    }

    private void publishEvent(NormalizedTicker ticker) {
        try {
            tickerEventPublisher.publish(ticker);
        } catch (Exception e) {
            log.error("RabbitMQ 발행 실패: {}/{}", ticker.exchange(), ticker.base(), e);
        }
    }

    private void publishToEngine(NormalizedTicker ticker) {
        try {
            engineInboxPublisher.publish(ticker);
        } catch (Exception e) {
            log.error("engine.inbox 발행 실패: {}/{}", ticker.exchange(), ticker.base(), e);
        }
    }
}
