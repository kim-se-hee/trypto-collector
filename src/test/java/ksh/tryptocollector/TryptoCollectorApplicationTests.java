package ksh.tryptocollector;

import ksh.tryptocollector.matching.CompensationScheduler;
import ksh.tryptocollector.metadata.ExchangeInitializer;
import ksh.tryptocollector.support.TestContainerConfiguration;
import ksh.tryptocollector.support.TestInfluxConfig;
import ksh.tryptocollector.support.TestRedissonConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestContainerConfiguration.class, TestRedissonConfig.class, TestInfluxConfig.class})
class TryptoCollectorApplicationTests {

    @MockitoBean
    ExchangeInitializer exchangeInitializer;

    @MockitoBean
    CompensationScheduler compensationScheduler;

    @Test
    void contextLoads() {
    }

}
