package ksh.tryptocollector.support;

import com.redis.testcontainers.RedisContainer;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class TestRedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisContainer redisContainer) {
        Config config = new Config();
        String address = "redis://" + redisContainer.getRedisHost() + ":" + redisContainer.getRedisPort();
        config.useSingleServer().setAddress(address);
        return Redisson.create(config);
    }
}
