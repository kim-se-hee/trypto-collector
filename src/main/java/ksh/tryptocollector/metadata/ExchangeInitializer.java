package ksh.tryptocollector.metadata;

import jakarta.annotation.PostConstruct;
import ksh.tryptocollector.exchange.binance.BinanceRestClient;
import ksh.tryptocollector.exchange.binance.BinanceWebSocketHandler;
import ksh.tryptocollector.exchange.bithumb.BithumbRestClient;
import ksh.tryptocollector.exchange.bithumb.BithumbTickerResponse;
import ksh.tryptocollector.exchange.bithumb.BithumbWebSocketHandler;
import ksh.tryptocollector.exchange.upbit.UpbitRestClient;
import ksh.tryptocollector.exchange.upbit.UpbitTickerResponse;
import ksh.tryptocollector.exchange.upbit.UpbitWebSocketHandler;
import ksh.tryptocollector.model.Exchange;
import ksh.tryptocollector.model.MarketInfo;
import ksh.tryptocollector.model.NormalizedTicker;
import ksh.tryptocollector.redis.MarketMetadataRedisRepository;
import ksh.tryptocollector.redis.TickerRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeInitializer {

    private final MarketInfoCache marketInfoCache;
    private final TickerRedisRepository tickerRedisRepository;
    private final MarketMetadataRedisRepository marketMetadataRedisRepository;
    private final UpbitRestClient upbitRestClient;
    private final BithumbRestClient bithumbRestClient;
    private final BinanceRestClient binanceRestClient;
    private final UpbitWebSocketHandler upbitWebSocketHandler;
    private final BithumbWebSocketHandler bithumbWebSocketHandler;
    private final BinanceWebSocketHandler binanceWebSocketHandler;

    private static final long MAX_BACKOFF_SECONDS = 60;
    private static final int CHANGE_RATE_SCALE = 8;
    private static final int THREAD_POOL_SIZE = 3;

    @PostConstruct
    void init() {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        executor.submit(() -> initWithRetry("업비트", this::loadAndConnectUpbit));
        executor.submit(() -> initWithRetry("빗썸", this::loadAndConnectBithumb));
        executor.submit(() -> initWithRetry("바이낸스", this::loadAndConnectBinance));
    }

    private void initWithRetry(String exchangeName, Runnable task) {
        int retryCount = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                task.run();
                return;
            } catch (Exception e) {
                retryCount++;
                log.warn("{} 초기화 실패, 재시도 #{}: {}", exchangeName, retryCount, e.getMessage());
                backoff(retryCount);
            }
        }
    }

    private void loadAndConnectUpbit() {
        List<MarketInfo> infos = upbitRestClient.fetchKrwMarkets();
        List<String> marketCodes = new ArrayList<>();
        Map<String, MarketInfo> infoByMarket = new HashMap<>();
        for (MarketInfo info : infos) {
            String marketCode = "KRW-" + info.base();
            marketInfoCache.put(Exchange.UPBIT, marketCode, info);
            marketCodes.add(marketCode);
            infoByMarket.put(marketCode, info);
        }
        log.info("업비트 마켓 메타데이터 로드 완료: {}개", infos.size());
        marketMetadataRedisRepository.save(Exchange.UPBIT, infos);

        List<UpbitTickerResponse> tickers = upbitRestClient.fetchKrwTickers(marketCodes);
        for (UpbitTickerResponse ticker : tickers) {
            MarketInfo info = infoByMarket.get(ticker.market());
            tickerRedisRepository.save(ticker.toNormalized(info.displayName()));
        }
        log.info("업비트 초기 시세 스냅샷 저장 완료: {}개", tickers.size());

        upbitWebSocketHandler.connect();
    }

    private void loadAndConnectBithumb() {
        List<MarketInfo> infos = bithumbRestClient.fetchKrwMarkets();
        List<String> marketCodes = new ArrayList<>();
        Map<String, MarketInfo> infoByMarket = new HashMap<>();
        for (MarketInfo info : infos) {
            String marketCode = "KRW-" + info.base();
            marketInfoCache.put(Exchange.BITHUMB, marketCode, info);
            marketCodes.add(marketCode);
            infoByMarket.put(marketCode, info);
        }
        log.info("빗썸 마켓 메타데이터 로드 완료: {}개", infos.size());
        marketMetadataRedisRepository.save(Exchange.BITHUMB, infos);

        List<BithumbTickerResponse> tickers = bithumbRestClient.fetchKrwTickers(marketCodes);
        for (BithumbTickerResponse ticker : tickers) {
            MarketInfo info = infoByMarket.get(ticker.market());
            tickerRedisRepository.save(ticker.toNormalized(info.displayName()));
        }
        log.info("빗썸 초기 시세 스냅샷 저장 완료: {}개", tickers.size());

        bithumbWebSocketHandler.connect();
    }

    private void loadAndConnectBinance() {
        var tickers = binanceRestClient.fetchUsdtTickers();
        for (var ticker : tickers) {
            String base = ticker.symbol().replace("USDT", "");
            MarketInfo info = new MarketInfo(base, "USDT", base + "/USDT", base);
            marketInfoCache.put(Exchange.BINANCE, ticker.symbol(), info);

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
            tickerRedisRepository.save(normalized);
        }
        log.info("바이낸스 마켓 메타데이터 로드 및 초기 스냅샷 저장 완료");
        marketMetadataRedisRepository.save(Exchange.BINANCE, marketInfoCache.getMarketInfos(Exchange.BINANCE));
        binanceWebSocketHandler.connect();
    }

    private void backoff(int retryCount) {
        try {
            long delay = Math.min(1L << retryCount, MAX_BACKOFF_SECONDS);
            Thread.sleep(delay * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
