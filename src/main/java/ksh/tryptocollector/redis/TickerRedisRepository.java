package ksh.tryptocollector.redis;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import ksh.tryptocollector.model.NormalizedTicker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
public class TickerRedisRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long ttlSeconds;
    private final String keyPrefix;

    public TickerRedisRepository(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${ticker.redis-ttl-seconds:30}") long ttlSeconds,
            @Value("${ticker.redis-key-prefix:ticker}") String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttlSeconds = ttlSeconds;
        this.keyPrefix = keyPrefix;
    }

    public void save(NormalizedTicker ticker) {
        String key = buildKey(ticker);
        String json;
        try {
            json = objectMapper.writeValueAsString(ticker);
        } catch (JacksonException e) {
            log.error("JSON 직렬화 실패: {}", ticker, e);
            return;
        }
        redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
    }

    private String buildKey(NormalizedTicker ticker) {
        return keyPrefix + ":" + ticker.exchange() + ":" + ticker.base() + "/" + ticker.quote();
    }
}
