package ksh.tryptocollector;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.client.WriteApiBlocking;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import ksh.tryptocollector.candle.CandleBuffer;
import ksh.tryptocollector.candle.CandleFlushScheduler;
import ksh.tryptocollector.exchange.TickerSinkProcessor;
import ksh.tryptocollector.metadata.ExchangeInitializer;
import ksh.tryptocollector.model.NormalizedTicker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.willThrow;

@SpringBootTest
@Testcontainers
class MetricsIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
    }

    @MockitoBean
    ExchangeInitializer exchangeInitializer;

    @MockitoBean
    InfluxDBClient influxDBClient;

    @MockitoBean
    WriteApiBlocking writeApiBlocking;

    @MockitoBean
    QueryApi queryApi;

    @Autowired
    TickerSinkProcessor sinkProcessor;

    @Autowired
    CandleBuffer candleBuffer;

    @Autowired
    CandleFlushScheduler candleFlushScheduler;

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
        sinkProcessor.process(createTicker());

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
    void 캔들_플러시시_타이머가_기록된다() {
        candleBuffer.update(createTicker());

        candleFlushScheduler.flush();

        Timer flushTimer = registry.find("candle.flush.duration").timer();
        assertThat(flushTimer).isNotNull();
        assertThat(flushTimer.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 캔들_플러시_실패시_실패_카운터가_증가한다() {
        candleBuffer.update(createTicker());
        willThrow(new RuntimeException("write error")).given(writeApiBlocking).writePoints(anyList());

        Counter failureCounter = registry.find("candle.flush.failure").counter();
        double before = failureCounter.count();

        candleFlushScheduler.flush();

        assertThat(failureCounter.count() - before).isEqualTo(1.0);
    }

    @Test
    void 직접_계측_카운터가_등록된다() {
        assertThat(registry.find("websocket.reconnect").counters()).isNotEmpty();
        assertThat(registry.find("rabbitmq.nack.count").counter()).isNotNull();
        assertThat(registry.find("ticker.parse.failure").counters()).isNotEmpty();
    }
}
