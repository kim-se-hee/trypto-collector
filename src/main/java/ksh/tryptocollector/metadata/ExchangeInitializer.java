package ksh.tryptocollector.metadata;

import jakarta.annotation.PostConstruct;
import ksh.tryptocollector.collector.RealtimePriceCollector;
import ksh.tryptocollector.redis.TickerRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeInitializer {

    private final MarketInfoCache marketInfoCache;
    private final RealtimePriceCollector realtimePriceCollector;
    private final TickerRedisRepository tickerRedisRepository;

    @PostConstruct
    void init() {
        loadUpbit().subscribe();
        loadBithumb().subscribe();
        loadBinance().subscribe();
    }

    Mono<Void> loadUpbit() {
        return Mono.empty();
    }

    Mono<Void> loadBithumb() {
        return Mono.empty();
    }

    Mono<Void> loadBinance() {
        return Mono.empty();
    }
}
