package ksh.tryptocollector.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SentinelServersConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("!test")
@Configuration
public class RedissonConfig {

    private static final String REDIS_PROTOCOL = "redis://";

    @Value("${spring.data.redis.sentinel.master}")
    private String masterName;

    @Value("${spring.data.redis.sentinel.nodes}")
    private String sentinelNodes;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        SentinelServersConfig sentinelConfig = config.useSentinelServers()
                .setMasterName(masterName)
                .setCheckSentinelsList(false);
        for (String node : sentinelNodes.split(",")) {
            sentinelConfig.addSentinelAddress(REDIS_PROTOCOL + node.trim());
        }
        return Redisson.create(config);
    }
}
