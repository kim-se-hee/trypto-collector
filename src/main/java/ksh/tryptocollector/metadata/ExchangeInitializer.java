package ksh.tryptocollector.metadata;

import jakarta.annotation.PostConstruct;
import ksh.tryptocollector.client.rest.BinanceRestClient;
import ksh.tryptocollector.client.rest.BithumbRestClient;
import ksh.tryptocollector.client.rest.UpbitRestClient;
import ksh.tryptocollector.collector.RealtimePriceCollector;
import ksh.tryptocollector.common.model.Exchange;
import ksh.tryptocollector.common.model.NormalizedTicker;
import ksh.tryptocollector.metadata.model.MarketInfo;
import ksh.tryptocollector.redis.TickerRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeInitializer {

    private final MarketInfoCache marketInfoCache;
    private final RealtimePriceCollector realtimePriceCollector;
    private final TickerRedisRepository tickerRedisRepository;
    private final UpbitRestClient upbitRestClient;
    private final BithumbRestClient bithumbRestClient;
    private final BinanceRestClient binanceRestClient;

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

    private static final int CHANGE_RATE_SCALE = 8;

    Mono<Void> loadBinance() {
        return binanceRestClient.fetchUsdtTickers()
                .doOnNext(ticker -> {
                    String base = ticker.symbol().replace("USDT", "");
                    MarketInfo info = new MarketInfo(base, "USDT", base + "/USDT", base);
                    marketInfoCache.put(Exchange.BINANCE, ticker.symbol(), info);
                })
                .flatMap(ticker -> {
                    String base = ticker.symbol().replace("USDT", "");
                    BigDecimal changeRate = new BigDecimal(ticker.priceChangePercent())
                            .divide(BigDecimal.valueOf(100), CHANGE_RATE_SCALE, RoundingMode.HALF_UP);
                    NormalizedTicker normalized = new NormalizedTicker(
                            Exchange.BINANCE.name(),
                            base, "USDT", base,
                            new BigDecimal(ticker.lastPrice()),
                            changeRate,
                            new BigDecimal(ticker.quoteVolume()),
                            System.currentTimeMillis()
                    );
                    return tickerRedisRepository.save(normalized);
                })
                .doOnComplete(() -> {
                    log.info("바이낸스 마켓 메타데이터 로드 및 초기 스냅샷 저장 완료");
                    realtimePriceCollector.connectBinance();
                })
                .then();
    }
}
