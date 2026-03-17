package ksh.tryptocollector.exchange.bithumb;

import ksh.tryptocollector.exchange.ExchangeTickerStream;
import ksh.tryptocollector.metadata.MarketInfoCache;
import ksh.tryptocollector.model.Exchange;
import ksh.tryptocollector.model.NormalizedTicker;
import ksh.tryptocollector.rabbitmq.TickerEventPublisher;
import ksh.tryptocollector.redis.TickerRedisRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@Slf4j
@Component
public class BithumbWebSocketHandler implements ExchangeTickerStream {
    private final ReactorNettyWebSocketClient webSocketClient;
    private final ObjectMapper objectMapper;
    private final MarketInfoCache marketInfoCache;
    private final TickerRedisRepository tickerRedisRepository;
    private final TickerEventPublisher tickerEventPublisher;
    private final String wsUrl;

    public BithumbWebSocketHandler(
            ReactorNettyWebSocketClient webSocketClient,
            ObjectMapper objectMapper,
            MarketInfoCache marketInfoCache,
            TickerRedisRepository tickerRedisRepository,
            TickerEventPublisher tickerEventPublisher,
            @Value("${exchange.bithumb.ws-url}") String wsUrl) {
        this.webSocketClient = webSocketClient;
        this.objectMapper = objectMapper;
        this.marketInfoCache = marketInfoCache;
        this.tickerRedisRepository = tickerRedisRepository;
        this.tickerEventPublisher = tickerEventPublisher;
        this.wsUrl = wsUrl;
    }

    @Override
    public Mono<Void> connect() {
        return webSocketClient.execute(URI.create(wsUrl), session -> {
                    String subscribeMessage = buildSubscribeMessage();
                    Mono<Void> send = session.send(
                            Mono.just(session.textMessage(subscribeMessage)));
                    Mono<Void> receive = session.receive()
                            .doOnNext(this::handleMessage)
                            .then();
                    return send.then(receive);
                })
                .doOnSubscribe(s -> log.info("빗썸 WebSocket 연결 시작"))
                .doOnError(e -> log.error("빗썸 WebSocket 연결 오류", e))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(60))
                        .doBeforeRetry(signal -> log.warn("빗썸 WebSocket 재연결 시도 #{}", signal.totalRetries() + 1)));
    }

    private String buildSubscribeMessage() {
        List<String> codes = marketInfoCache.getSymbolCodes(Exchange.BITHUMB);
        log.info("빗썸 WebSocket 구독: {} 마켓", codes.size());
        return "[{\"ticket\":\"trypto-collector\"},{\"type\":\"ticker\",\"codes\":" +
                objectMapper.writeValueAsString(codes) + "}]";
    }

    private void handleMessage(WebSocketMessage message) {
        try {
            String payload = message.getPayloadAsText();
            BithumbTickerMessage ticker = objectMapper.readValue(payload, BithumbTickerMessage.class);
            marketInfoCache.find(Exchange.BITHUMB, ticker.code())
                    .ifPresent(meta -> {
                        NormalizedTicker normalized = ticker.toNormalized(meta.displayName());
                        Mono.when(
                                tickerRedisRepository.save(normalized),
                                tickerEventPublisher.publish(normalized)
                        ).subscribe();
                    });
        } catch (Exception e) {
            log.debug("빗썸 메시지 처리 실패: {}", e.getMessage());
        }
    }
}
