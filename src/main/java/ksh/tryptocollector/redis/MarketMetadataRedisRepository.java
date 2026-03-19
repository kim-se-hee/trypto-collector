package ksh.tryptocollector.redis;

import ksh.tryptocollector.model.Exchange;
import ksh.tryptocollector.model.MarketInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Slf4j
@Component
public class MarketMetadataRedisRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;

    public MarketMetadataRedisRepository(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${market-meta.redis-key-prefix:market-meta}") String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.keyPrefix = keyPrefix;
    }

    public void save(Exchange exchange, List<MarketInfo> marketInfos) {
        String key = keyPrefix + ":" + exchange.name();
        String json;
        try {
            json = objectMapper.writeValueAsString(marketInfos);
        } catch (JacksonException e) {
            log.error("마켓 메타데이터 직렬화 실패: exchange={}", exchange, e);
            return;
        }
        redisTemplate.opsForValue().set(key, json);
        log.info("{} 마켓 메타데이터 Redis 저장 완료: {}개", exchange, marketInfos.size());
    }
}
