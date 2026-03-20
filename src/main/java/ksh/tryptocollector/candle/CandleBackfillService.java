package ksh.tryptocollector.candle;

import com.influxdb.client.QueryApi;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import ksh.tryptocollector.exchange.binance.BinanceRestClient;
import ksh.tryptocollector.exchange.bithumb.BithumbCandleResponse;
import ksh.tryptocollector.exchange.bithumb.BithumbRestClient;
import ksh.tryptocollector.exchange.upbit.UpbitCandleResponse;
import ksh.tryptocollector.exchange.upbit.UpbitRestClient;
import ksh.tryptocollector.metadata.MarketInfoCache;
import ksh.tryptocollector.model.Exchange;
import ksh.tryptocollector.model.MarketInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CandleBackfillService {

    private static final String MEASUREMENT = "candle_1m";
    private static final int UPBIT_PAGE_SIZE = 200;
    private static final int BINANCE_PAGE_SIZE = 1000;
    private static final long REQUEST_INTERVAL_MS = 100;

    private final UpbitRestClient upbitRestClient;
    private final BithumbRestClient bithumbRestClient;
    private final BinanceRestClient binanceRestClient;
    private final MarketInfoCache marketInfoCache;
    private final WriteApiBlocking writeApiBlocking;
    private final QueryApi queryApi;

    @Value("${influxdb.bucket}")
    private String bucket;

    @Value("${influxdb.org}")
    private String influxOrg;

    public void backfill(Exchange exchange) {
        Instant lastCandleTime = findLastCandleTime(exchange);
        if (lastCandleTime == null) {
            log.info("{} InfluxDB에 캔들 데이터 없음, 백필 건너뜀", exchange);
            return;
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        long gapMinutes = ChronoUnit.MINUTES.between(lastCandleTime, now);
        if (gapMinutes <= 1) {
            log.info("{} 캔들 갭 없음, 백필 건너뜀", exchange);
            return;
        }

        log.info("{} 갭 복구 시작: {} ~ {} ({}분)", exchange, lastCandleTime, now, gapMinutes);

        List<String> symbolCodes = marketInfoCache.getSymbolCodes(exchange);
        int totalCandles = 0;

        for (String symbolCode : symbolCodes) {
            try {
                MarketInfo info = marketInfoCache.find(exchange, symbolCode).orElseThrow();
                totalCandles += backfillCoin(exchange, symbolCode, info, lastCandleTime, now);
            } catch (Exception e) {
                log.warn("{} {} 갭 복구 실패: {}", exchange, symbolCode, e.getMessage());
            }
        }

        log.info("{} 갭 복구 완료: 총 {}건", exchange, totalCandles);
    }

    private int backfillCoin(Exchange exchange, String symbolCode, MarketInfo info,
                             Instant from, Instant to) {
        List<Point> points = switch (exchange) {
            case UPBIT -> fetchUpbitCandles(symbolCode, info.pair(), from, to);
            case BITHUMB -> fetchBithumbCandles(info, from, to);
            case BINANCE -> fetchBinanceCandles(symbolCode, info.pair(), from, to);
        };

        if (points.isEmpty()) {
            return 0;
        }

        writeApiBlocking.writePoints(points);
        log.debug("{} {} 갭 복구: {}건", exchange, info.pair(), points.size());
        return points.size();
    }

    private List<Point> fetchUpbitCandles(String market, String coin, Instant from, Instant to) {
        List<Point> allPoints = new ArrayList<>();
        String currentTo = formatUtcTime(to);

        while (true) {
            List<UpbitCandleResponse> candles = upbitRestClient.fetchMinuteCandles(
                    market, currentTo, UPBIT_PAGE_SIZE);
            if (candles.isEmpty()) {
                break;
            }

            boolean reachedStart = false;
            for (UpbitCandleResponse c : candles) {
                Instant candleTime = parseUtcTime(c.candleDateTimeUtc());
                if (!candleTime.isAfter(from)) {
                    reachedStart = true;
                    break;
                }
                allPoints.add(toPoint("UPBIT", coin, candleTime,
                        c.openingPrice(), c.highPrice(), c.lowPrice(), c.tradePrice()));
            }

            if (reachedStart || candles.size() < UPBIT_PAGE_SIZE) {
                break;
            }

            currentTo = candles.getLast().candleDateTimeUtc();
            sleep(REQUEST_INTERVAL_MS);
        }

        return allPoints;
    }

    private List<Point> fetchBithumbCandles(MarketInfo info, Instant from, Instant to) {
        BithumbCandleResponse response = bithumbRestClient.fetchMinuteCandles(info.base());
        if (response == null || response.data() == null) {
            return List.of();
        }

        List<Point> points = new ArrayList<>();
        String coin = info.pair();

        for (List<Object> item : response.data()) {
            long tsMs = ((Number) item.get(0)).longValue();
            Instant candleTime = Instant.ofEpochMilli(tsMs).truncatedTo(ChronoUnit.MINUTES);

            if (candleTime.isAfter(from) && candleTime.isBefore(to)) {
                double open = toDouble(item.get(1));
                double close = toDouble(item.get(2));
                double high = toDouble(item.get(3));
                double low = toDouble(item.get(4));
                points.add(toPoint("BITHUMB", coin, candleTime, open, high, low, close));
            }
        }

        sleep(REQUEST_INTERVAL_MS);
        return points;
    }

    private List<Point> fetchBinanceCandles(String symbol, String coin, Instant from, Instant to) {
        List<Point> allPoints = new ArrayList<>();
        long currentStart = from.toEpochMilli();
        long endMs = to.toEpochMilli();

        while (currentStart < endMs) {
            List<List<Object>> candles = binanceRestClient.fetchMinuteCandles(
                    symbol, currentStart, endMs, BINANCE_PAGE_SIZE);
            if (candles.isEmpty()) {
                break;
            }

            for (List<Object> c : candles) {
                long openTimeMs = ((Number) c.get(0)).longValue();
                Instant candleTime = Instant.ofEpochMilli(openTimeMs);
                double open = toDouble(c.get(1));
                double high = toDouble(c.get(2));
                double low = toDouble(c.get(3));
                double close = toDouble(c.get(4));
                allPoints.add(toPoint("BINANCE", coin, candleTime, open, high, low, close));
            }

            List<Object> lastCandle = candles.getLast();
            long lastOpenTime = ((Number) lastCandle.get(0)).longValue();
            currentStart = lastOpenTime + 60_000;

            if (candles.size() < BINANCE_PAGE_SIZE) {
                break;
            }

            sleep(REQUEST_INTERVAL_MS);
        }

        return allPoints;
    }

    private Instant findLastCandleTime(Exchange exchange) {
        String query = """
                from(bucket: "%s")
                  |> range(start: -30d)
                  |> filter(fn: (r) => r._measurement == "candle_1m" and r._field == "close" and r.exchange == "%s")
                  |> group()
                  |> last()
                  |> keep(columns: ["_time"])
                """.formatted(bucket, exchange.name());

        List<FluxTable> tables = queryApi.query(query, influxOrg);

        Instant latest = null;
        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                Instant time = record.getTime();
                if (time != null && (latest == null || time.isAfter(latest))) {
                    latest = time;
                }
            }
        }
        return latest;
    }

    private Point toPoint(String exchange, String coin, Instant timestamp,
                          double open, double high, double low, double close) {
        return Point.measurement(MEASUREMENT)
                .addTag("exchange", exchange)
                .addTag("coin", coin)
                .addField("open", open)
                .addField("high", high)
                .addField("low", low)
                .addField("close", close)
                .time(timestamp, WritePrecision.S);
    }

    private String formatUtcTime(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDateTime().toString();
    }

    private Instant parseUtcTime(String utcTime) {
        return LocalDateTime.parse(utcTime).toInstant(ZoneOffset.UTC);
    }

    private double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
