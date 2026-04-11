package ksh.tryptocollector;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import ksh.tryptocollector.exchange.TickerSinkProcessor;
import ksh.tryptocollector.matching.CompensationScheduler;
import ksh.tryptocollector.metadata.ExchangeInitializer;
import ksh.tryptocollector.model.NormalizedTicker;
import ksh.tryptocollector.support.TestContainerConfiguration;
import ksh.tryptocollector.support.TestInfluxConfig;
import ksh.tryptocollector.support.TestRedissonConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestContainerConfiguration.class, TestRedissonConfig.class, TestInfluxConfig.class})
class MetricsIntegrationTest {

    @MockitoBean
    ExchangeInitializer exchangeInitializer;

    @MockitoBean
    CompensationScheduler compensationScheduler;

    @Autowired
    TickerSinkProcessor sinkProcessor;

    @Autowired
    MeterRegistry registry;

    private NormalizedTicker createTicker() {
        return new NormalizedTicker(
                "UPBIT", "BTC", "KRW", "비트코인",
                new BigDecimal("50000000"), BigDecimal.ZERO,
                new BigDecimal("1000000"), System.currentTimeMillis()
        );
    }

    @Test
    void 시세_처리시_파이프라인_메트릭이_기록된다() {
        sinkProcessor.process(createTicker(), System.nanoTime());

        Timer latency = registry.find("ticker.latency").tag("exchange", "UPBIT").timer();
        assertThat(latency).isNotNull();
        assertThat(latency.count()).isGreaterThanOrEqualTo(1);

        Timer redisWrite = registry.find("redis.write.time").timer();
        assertThat(redisWrite).isNotNull();
        assertThat(redisWrite.count()).isGreaterThanOrEqualTo(1);

        Counter rabbitPublish = registry.find("rabbitmq.publish").tag("exchange", "UPBIT").counter();
        assertThat(rabbitPublish).isNotNull();
        assertThat(rabbitPublish.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 직접_계측_카운터가_등록된다() {
        assertThat(registry.find("websocket.reconnect").counters()).isNotEmpty();
        assertThat(registry.find("rabbitmq.nack.count").counter()).isNotNull();
        assertThat(registry.find("ticker.parse.failure").counters()).isNotEmpty();
    }
}
