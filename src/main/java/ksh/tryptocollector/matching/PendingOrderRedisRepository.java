package ksh.tryptocollector.matching;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class PendingOrderRedisRepository {

    private static final String KEY_PREFIX = "pending:orders:";

    private final StringRedisTemplate redisTemplate;

    public Set<String> findMatchedBuyOrders(String exchange, String symbol, BigDecimal currentPrice) {
        String key = buildKey(exchange, symbol, "BUY");
        return redisTemplate.opsForZSet()
                .rangeByScore(key, currentPrice.doubleValue(), Double.POSITIVE_INFINITY);
    }

    public Set<String> findMatchedSellOrders(String exchange, String symbol, BigDecimal currentPrice) {
        String key = buildKey(exchange, symbol, "SELL");
        return redisTemplate.opsForZSet()
                .rangeByScore(key, Double.NEGATIVE_INFINITY, currentPrice.doubleValue());
    }

    public void add(String exchange, String symbol, String side, String orderId, double price) {
        String key = buildKey(exchange, symbol, side);
        redisTemplate.opsForZSet().add(key, orderId, price);
    }

    public void remove(String exchange, String symbol, String side, String orderId) {
        String key = buildKey(exchange, symbol, side);
        redisTemplate.opsForZSet().remove(key, orderId);
    }

    public void removeAll(String exchange, String symbol, String side, Set<String> orderIds) {
        if (orderIds.isEmpty()) {
            return;
        }
        String key = buildKey(exchange, symbol, side);
        redisTemplate.opsForZSet().remove(key, orderIds.toArray());
    }

    private String buildKey(String exchange, String symbol, String side) {
        return KEY_PREFIX + exchange + ":" + symbol + ":" + side;
    }
}
