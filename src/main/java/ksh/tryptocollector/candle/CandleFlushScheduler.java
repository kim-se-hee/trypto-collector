package ksh.tryptocollector.candle;

import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.write.Point;
import com.influxdb.client.domain.WritePrecision;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class CandleFlushScheduler {
    private static final String MEASUREMENT = "candle_1m";

    private final CandleBuffer candleBuffer;
    private final WriteApiBlocking writeApiBlocking;
    private final Counter flushFailureCounter;

    public CandleFlushScheduler(CandleBuffer candleBuffer, WriteApiBlocking writeApiBlocking,
                                MeterRegistry registry) {
        this.candleBuffer = candleBuffer;
        this.writeApiBlocking = writeApiBlocking;
        this.flushFailureCounter = Counter.builder("candle.flush.failure")
                .description("InfluxDB write 실패 횟수")
                .register(registry);
    }

    @Scheduled(cron = "0 * * * * *")
    @Timed(value = "candle.flush.duration")
    public void flush() {
        Map<CandleBuffer.CandleKey, OhlcAccumulator> snapshot = candleBuffer.flushAll();
        if (snapshot.isEmpty()) {
            return;
        }

        Instant timestamp = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(1, ChronoUnit.MINUTES);

        List<Point> points = snapshot.entrySet().stream()
                .map(entry -> toPoint(entry.getKey(), entry.getValue(), timestamp))
                .toList();

        try {
            writeApiBlocking.writePoints(points);
            log.debug("InfluxDB 분봉 write: {} 건", points.size());
        } catch (Exception e) {
            flushFailureCounter.increment();
            log.warn("InfluxDB 분봉 write 실패: {}", e.getMessage());
        }
    }

    private Point toPoint(CandleBuffer.CandleKey key, OhlcAccumulator ohlc, Instant timestamp) {
        return Point.measurement(MEASUREMENT)
                .addTag("exchange", key.exchange())
                .addTag("coin", key.coin())
                .addField("open", ohlc.open().doubleValue())
                .addField("high", ohlc.high().doubleValue())
                .addField("low", ohlc.low().doubleValue())
                .addField("close", ohlc.close().doubleValue())
                .time(timestamp, WritePrecision.S);
    }
}
