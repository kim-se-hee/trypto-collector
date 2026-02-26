package ksh.tryptocollector.client.websocket;

import ksh.tryptocollector.client.websocket.dto.BinanceTickerMessage;
import ksh.tryptocollector.common.model.Exchange;
import ksh.tryptocollector.metadata.MarketInfoCache;
import ksh.tryptocollector.metadata.model.MarketInfo;
import ksh.tryptocollector.redis.TickerRedisRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;

@Slf4j
@Component
public class BinanceWebSocketHandler implements ExchangeTickerStream {

    private static final int BOUNDED_CONCURRENCY = 32;

    private final ReactorNettyWebSocketClient webSocketClient;
    private final ObjectMapper objectMapper;
    private final MarketInfoCache marketInfoCache;
    private final TickerRedisRepository tickerRedisRepository;
    private final String wsUrl;

    public BinanceWebSocketHandler(
            ReactorNettyWebSocketClient webSocketClient,
            ObjectMapper objectMapper,
            MarketInfoCache marketInfoCache,
            TickerRedisRepository tickerRedisRepository,
            @Value("${exchange.binance.ws-url}") String wsUrl) {
        this.webSocketClient = webSocketClient;
        this.objectMapper = objectMapper;
        this.marketInfoCache = marketInfoCache;
        this.tickerRedisRepository = tickerRedisRepository;
        this.wsUrl = wsUrl;
    }

    @Override
    public Mono<Void> connect() {
        return webSocketClient.execute(URI.create(wsUrl), session ->
                        session.receive()
                                .flatMap(this::handleMessage, BOUNDED_CONCURRENCY)
                                .then())
                .doOnSubscribe(s -> log.info("바이낸스 WebSocket 연결 시작"))
                .doOnError(e -> log.error("바이낸스 WebSocket 연결 오류", e))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(60))
                        .doBeforeRetry(signal -> log.warn("바이낸스 WebSocket 재연결 시도 #{}", signal.totalRetries() + 1)));
    }

    private Flux<Void> handleMessage(WebSocketMessage message) {
        try {
            String payload = message.getPayloadAsText();
            BinanceTickerMessage[] tickers = objectMapper.readValue(
                    payload, BinanceTickerMessage[].class);
            return Flux.fromArray(tickers)
                    .flatMap(ticker -> {
                        return marketInfoCache.find(Exchange.BINANCE, ticker.symbol())
                                .map(meta -> tickerRedisRepository
                                        .save(ticker.toNormalized(meta.displayName()))
                                        .then())
                                .orElse(Mono.empty());
                    }, BOUNDED_CONCURRENCY);
        } catch (Exception e) {
            log.debug("바이낸스 메시지 처리 실패: {}", e.getMessage());
            return Flux.empty();
        }
    }
}
