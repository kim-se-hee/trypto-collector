package ksh.tryptocollector.client.websocket;

import ksh.tryptocollector.client.websocket.dto.UpbitTickerMessage;
import ksh.tryptocollector.common.model.Exchange;
import ksh.tryptocollector.metadata.MarketInfoCache;
import ksh.tryptocollector.redis.TickerRedisRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPInputStream;

@Slf4j
@Component
public class UpbitWebSocketHandler implements ExchangeTickerStream {

    private static final int GZIP_BUFFER_SIZE = 1024;

    private final ReactorNettyWebSocketClient webSocketClient;
    private final ObjectMapper objectMapper;
    private final MarketInfoCache marketInfoCache;
    private final TickerRedisRepository tickerRedisRepository;
    private final String wsUrl;

    public UpbitWebSocketHandler(
            ReactorNettyWebSocketClient webSocketClient,
            ObjectMapper objectMapper,
            MarketInfoCache marketInfoCache,
            TickerRedisRepository tickerRedisRepository,
            @Value("${exchange.upbit.ws-url}") String wsUrl) {
        this.webSocketClient = webSocketClient;
        this.objectMapper = objectMapper;
        this.marketInfoCache = marketInfoCache;
        this.tickerRedisRepository = tickerRedisRepository;
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
                .doOnSubscribe(s -> log.info("업비트 WebSocket 연결 시작"))
                .doOnError(e -> log.error("업비트 WebSocket 연결 오류", e))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(60))
                        .doBeforeRetry(signal -> log.warn("업비트 WebSocket 재연결 시도 #{}", signal.totalRetries() + 1)));
    }

    private String buildSubscribeMessage() {
        List<String> codes = marketInfoCache.getSymbolCodes(Exchange.UPBIT);
        log.info("업비트 WebSocket 구독: {} 마켓", codes.size());
        return "[{\"ticket\":\"trypto-collector\"},{\"type\":\"ticker\",\"codes\":" +
                objectMapper.writeValueAsString(codes) + "}]";
    }

    private void handleMessage(WebSocketMessage message) {
        try {
            byte[] payload = extractPayload(message);
            byte[] decompressed = decompressIfNeeded(payload);
            UpbitTickerMessage ticker = objectMapper.readValue(decompressed, UpbitTickerMessage.class);
            marketInfoCache.find(Exchange.UPBIT, ticker.code())
                    .ifPresent(meta -> tickerRedisRepository
                            .save(ticker.toNormalized(meta.displayName()))
                            .subscribe());
        } catch (Exception e) {
            log.debug("업비트 메시지 처리 실패: {}", e.getMessage());
        }
    }

    private byte[] extractPayload(WebSocketMessage message) {
        org.springframework.core.io.buffer.DataBuffer buffer = message.getPayload();
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        return bytes;
    }

    private byte[] decompressIfNeeded(byte[] bytes) throws IOException {
        if (bytes.length > 2 && bytes[0] == (byte) 0x1f && bytes[1] == (byte) 0x8b) {
            return decompress(bytes);
        }
        return bytes;
    }

    private byte[] decompress(byte[] compressed) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[GZIP_BUFFER_SIZE];
            int len;
            while ((len = gis.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        }
    }
}
