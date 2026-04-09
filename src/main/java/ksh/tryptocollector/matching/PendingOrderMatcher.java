package ksh.tryptocollector.matching;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import ksh.tryptocollector.model.NormalizedTicker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingOrderMatcher {

    private final PendingOrderRedisRepository pendingOrderRedisRepository;
    private final PendingOrderDbRepository pendingOrderDbRepository;
    private final MatchedOrderPublisher matchedOrderPublisher;
    private final CircuitBreaker redisCircuitBreaker;
    private final MeterRegistry meterRegistry;

    public void match(NormalizedTicker ticker) {
        String exchange = ticker.exchange();
        String symbol = ticker.base() + "/" + ticker.quote();

        MatchResult result = findMatchedOrders(exchange, symbol, ticker.base(), ticker.lastPrice());
        if (result.isEmpty()) return;

        boolean acked = matchedOrderPublisher.publish(new MatchedOrderMessage(result.items()));

        if (acked) {
            removeMatchedFromRedis(result, exchange, symbol);
            log.info("주문 매칭 완료: {}/{} {}건", exchange, symbol, result.items().size());
        } else {
            log.warn("주문 매칭 발행 실패: {}/{} {}건", exchange, symbol, result.items().size());
        }

        recordMetrics(exchange, acked, result.items().size());
    }

    private MatchResult findMatchedOrders(String exchange, String symbol, String base, BigDecimal price) {
        try {
            Set<String> buyOrderIds = redisCircuitBreaker.executeSupplier(() ->
                    pendingOrderRedisRepository.findMatchedBuyOrders(exchange, symbol, price));
            Set<String> sellOrderIds = redisCircuitBreaker.executeSupplier(() ->
                    pendingOrderRedisRepository.findMatchedSellOrders(exchange, symbol, price));
            return MatchResult.fromRedis(buyOrderIds, sellOrderIds, price);
        } catch (CallNotPermittedException e) {
            log.warn("Redis 서킷 OPEN, DB 폴백: {}/{}", exchange, symbol);
            return findFromDb(exchange, base, price);
        } catch (Exception e) {
            log.warn("Redis 매칭 실패, DB 폴백: {}/{}", exchange, symbol);
            return findFromDb(exchange, base, price);
        }
    }

    private MatchResult findFromDb(String exchange, String base, BigDecimal price) {
        List<Long> buyIds = pendingOrderDbRepository.findMatchedOrderIds(exchange, base, "BUY", price);
        List<Long> sellIds = pendingOrderDbRepository.findMatchedOrderIds(exchange, base, "SELL", price);
        return MatchResult.fromDb(buyIds, sellIds, price);
    }

    private void removeMatchedFromRedis(MatchResult result, String exchange, String symbol) {
        if (!result.buyOrderIds().isEmpty()) {
            pendingOrderRedisRepository.removeAll(exchange, symbol, "BUY", result.buyOrderIds());
        }
        if (!result.sellOrderIds().isEmpty()) {
            pendingOrderRedisRepository.removeAll(exchange, symbol, "SELL", result.sellOrderIds());
        }
    }

    private void recordMetrics(String exchange, boolean acked, int count) {
        Counter.builder("matching.matched.count")
                .tag("exchange", exchange)
                .tag("acked", String.valueOf(acked))
                .register(meterRegistry)
                .increment(count);
    }
}
