package ksh.tryptocollector.redis;

import io.micrometer.core.annotation.Timed;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import ksh.tryptocollector.model.NormalizedTicker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TickerRedisRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;

    public TickerRedisRepository(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${ticker.redis-key-prefix:ticker}") String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.keyPrefix = keyPrefix;
    }

    @Timed(value = "redis.write.time")
    public void save(NormalizedTicker ticker) {
        String key = buildKey(ticker);
        String json;
        try {
            json = objectMapper.writeValueAsString(ticker);
        } catch (JacksonException e) {
            log.error("JSON 직렬화 실패: {}", ticker, e);
            return;
        }
        redisTemplate.opsForValue().set(key, json);
    }

    private String buildKey(NormalizedTicker ticker) {
        return keyPrefix + ":" + ticker.exchange() + ":" + ticker.base() + "/" + ticker.quote();
    }
}
