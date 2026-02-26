package ksh.tryptocollector.metadata;

import jakarta.annotation.PostConstruct;
import ksh.tryptocollector.client.rest.BithumbRestClient;
import ksh.tryptocollector.client.rest.UpbitRestClient;
import ksh.tryptocollector.collector.RealtimePriceCollector;
import ksh.tryptocollector.common.model.Exchange;
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
    private final UpbitRestClient upbitRestClient;
    private final BithumbRestClient bithumbRestClient;

    @PostConstruct
    void init() {
        loadUpbit().subscribe();
        loadBithumb().subscribe();
        loadBinance().subscribe();
    }

    Mono<Void> loadUpbit() {
        return upbitRestClient.fetchKrwMarkets()
                .doOnNext(info -> marketInfoCache.put(Exchange.UPBIT, "KRW-" + info.base(), info))
                .doOnComplete(() -> {
                    log.info("업비트 마켓 메타데이터 로드 완료");
                    realtimePriceCollector.connectUpbit();
                })
                .then();
    }

    Mono<Void> loadBithumb() {
        return bithumbRestClient.fetchKrwMarkets()
                .doOnNext(info -> marketInfoCache.put(Exchange.BITHUMB, "KRW-" + info.base(), info))
                .doOnComplete(() -> {
                    log.info("빗썸 마켓 메타데이터 로드 완료");
                    realtimePriceCollector.connectBithumb();
                })
                .then();
    }

    Mono<Void> loadBinance() {
        return Mono.empty();
    }
}
