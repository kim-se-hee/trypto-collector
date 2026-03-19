package ksh.tryptocollector.exchange.upbit;

import ksh.tryptocollector.exchange.ExchangeTickerStream;
import ksh.tryptocollector.exchange.TickerSinkProcessor;
import ksh.tryptocollector.metadata.MarketInfoCache;
import ksh.tryptocollector.model.Exchange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.zip.GZIPInputStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpbitWebSocketHandler implements ExchangeTickerStream {
    private static final int GZIP_BUFFER_SIZE = 1024;
    private static final long MAX_BACKOFF_SECONDS = 60;

    private final ObjectMapper objectMapper;
    private final MarketInfoCache marketInfoCache;
    private final TickerSinkProcessor tickerSinkProcessor;

    @Value("${exchange.upbit.ws-url}")
    private String wsUrl;

    @Override
    public void connect() {
        int retryCount = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                CountDownLatch closeLatch = new CountDownLatch(1);
                HttpClient httpClient = HttpClient.newHttpClient();
                WebSocket ws = httpClient.newWebSocketBuilder()
                        .buildAsync(URI.create(wsUrl), new UpbitListener(closeLatch))
                        .join();
                String subscribeMessage = buildSubscribeMessage();
                ws.sendText(subscribeMessage, true);
                log.info("업비트 WebSocket 연결 시작");
                retryCount = 0;
                closeLatch.await();
            } catch (Exception e) {
                log.warn("업비트 WebSocket 연결 끊김, 재연결 시도 #{}", retryCount + 1, e);
            }
            backoff(retryCount++);
        }
    }

    private String buildSubscribeMessage() {
        List<String> codes = marketInfoCache.getSymbolCodes(Exchange.UPBIT);
        log.info("업비트 WebSocket 구독: {} 마켓", codes.size());
        return "[{\"ticket\":\"trypto-collector\"},{\"type\":\"ticker\",\"codes\":" +
                objectMapper.writeValueAsString(codes) + "}]";
    }

    private void handleMessage(byte[] payload) {
        try {
            byte[] decompressed = decompressIfNeeded(payload);
            UpbitTickerMessage ticker = objectMapper.readValue(decompressed, UpbitTickerMessage.class);
            marketInfoCache.find(Exchange.UPBIT, ticker.code())
                    .ifPresent(meta -> tickerSinkProcessor.process(ticker.toNormalized(meta.displayName())));
        } catch (Exception e) {
            log.debug("업비트 메시지 처리 실패: {}", e.getMessage());
        }
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

    private void backoff(int retryCount) {
        try {
            long delay = Math.min(1L << retryCount, MAX_BACKOFF_SECONDS);
            Thread.sleep(delay * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private class UpbitListener implements WebSocket.Listener {
        private final CountDownLatch closeLatch;
        private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();

        UpbitListener(CountDownLatch closeLatch) {
            this.closeLatch = closeLatch;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            binaryBuffer.write(bytes, 0, bytes.length);
            if (last) {
                byte[] payload = binaryBuffer.toByteArray();
                binaryBuffer.reset();
                handleMessage(payload);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            handleMessage(data.toString().getBytes());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("업비트 WebSocket 종료: statusCode={}, reason={}", statusCode, reason);
            closeLatch.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("업비트 WebSocket 오류", error);
            closeLatch.countDown();
        }
    }
}
