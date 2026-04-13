package ksh.tryptocollector.exchange;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import ksh.tryptocollector.matching.PendingOrderMatcher;
import ksh.tryptocollector.model.NormalizedTicker;
import ksh.tryptocollector.rabbitmq.TickerEventPublisher;
import ksh.tryptocollector.redis.TickerRedisRepository;
import ksh.tryptocollector.tick.TickRawWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class TickerSinkProcessor {
    private final TickerRedisRepository tickerRedisRepository;
    private final TickerEventPublisher tickerEventPublisher;
    private final TickRawWriter tickRawWriter;
    private final PendingOrderMatcher pendingOrderMatcher;
    private final CircuitBreaker redisCircuitBreaker;
    private final MeterRegistry registry;

    public void process(NormalizedTicker ticker, long receivedAtNanos) {
        writeRawTick(ticker);
        saveToRedis(ticker);
        publishEvent(ticker);
        matchOrders(ticker);
        recordLatency(ticker.exchange(), receivedAtNanos);
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

    private void matchOrders(NormalizedTicker ticker) {
        try {
            pendingOrderMatcher.match(ticker);
        } catch (Exception e) {
            log.error("주문 매칭 실패: {}/{}", ticker.exchange(), ticker.base(), e);
        }
    }

    private void recordLatency(String exchange, long receivedAtNanos) {
        long elapsed = System.nanoTime() - receivedAtNanos;
        registry.timer("ticker.latency", "exchange", exchange)
                .record(elapsed, TimeUnit.NANOSECONDS);
    }
}
