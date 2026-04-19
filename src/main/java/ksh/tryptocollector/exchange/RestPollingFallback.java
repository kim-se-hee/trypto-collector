package ksh.tryptocollector.exchange;

import ksh.tryptocollector.metadata.MarketInfoCache;
import ksh.tryptocollector.model.Exchange;
import ksh.tryptocollector.model.NormalizedTicker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class RestPollingFallback {
    private static final long POLL_INTERVAL_MS = 200;

    private final Map<Exchange, ExchangeTickerPoller> pollers;
    private final MarketInfoCache marketInfoCache;
    private final TickerSinkProcessor tickerSinkProcessor;
    private final TaskScheduler taskScheduler;

    private final Map<Exchange, ScheduledFuture<?>> pollingTasks = new ConcurrentHashMap<>();

    public RestPollingFallback(
            List<ExchangeTickerPoller> pollerList,
            MarketInfoCache marketInfoCache,
            TickerSinkProcessor tickerSinkProcessor,
            TaskScheduler taskScheduler) {
        this.pollers = pollerList.stream()
                .collect(Collectors.toUnmodifiableMap(
                        ExchangeTickerPoller::exchange,
                        Function.identity()));
        this.marketInfoCache = marketInfoCache;
        this.tickerSinkProcessor = tickerSinkProcessor;
        this.taskScheduler = taskScheduler;
    }

    public void start(Exchange exchange) {
        if (pollingTasks.containsKey(exchange)) {
            return;
        }
        ScheduledFuture<?> task = taskScheduler.scheduleWithFixedDelay(
                () -> pollOnce(exchange),
                Duration.ofMillis(POLL_INTERVAL_MS)
        );
        pollingTasks.put(exchange, task);
        log.info("{} REST 폴링 폴백 시작", exchange);
    }

    public void stop(Exchange exchange) {
        ScheduledFuture<?> task = pollingTasks.remove(exchange);
        if (task != null) {
            task.cancel(false);
            log.info("{} REST 폴링 폴백 중지", exchange);
        }
    }

    private void pollOnce(Exchange exchange) {
        try {
            List<NormalizedTicker> tickers = fetchTickers(exchange);
            for (NormalizedTicker ticker : tickers) {
                tickerSinkProcessor.process(ticker);
            }
        } catch (Exception e) {
            log.warn("{} REST 폴링 실패: {}", exchange, e.getMessage(), e);
        }
    }

    private List<NormalizedTicker> fetchTickers(Exchange exchange) {
        List<String> symbolCodes = marketInfoCache.getSymbolCodes(exchange);
        if (symbolCodes.isEmpty()) {
            return List.of();
        }
        ExchangeTickerPoller poller = pollers.get(exchange);
        if (poller == null) {
            throw new IllegalStateException("등록된 Poller 없음: " + exchange);
        }
        return poller.fetch(symbolCodes).stream()
                .map(r -> marketInfoCache.find(exchange, r.code())
                        .map(meta -> r.toNormalized(meta.displayName()))
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }
}
