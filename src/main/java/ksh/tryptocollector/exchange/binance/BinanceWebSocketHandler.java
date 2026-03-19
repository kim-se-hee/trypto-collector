package ksh.tryptocollector.exchange.binance;

import ksh.tryptocollector.exchange.ExchangeTickerStream;
import ksh.tryptocollector.exchange.TickerSinkProcessor;
import ksh.tryptocollector.metadata.MarketInfoCache;
import ksh.tryptocollector.model.Exchange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceWebSocketHandler implements ExchangeTickerStream {
    private static final long MAX_BACKOFF_SECONDS = 60;

    private final ObjectMapper objectMapper;
    private final MarketInfoCache marketInfoCache;
    private final TickerSinkProcessor tickerSinkProcessor;

    @Value("${exchange.binance.ws-url}")
    private String wsUrl;

    @Override
    public void connect() {
        int retryCount = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                CountDownLatch closeLatch = new CountDownLatch(1);
                HttpClient httpClient = HttpClient.newHttpClient();
                WebSocket ws = httpClient.newWebSocketBuilder()
                        .buildAsync(URI.create(wsUrl), new BinanceListener(closeLatch))
                        .join();
                log.info("바이낸스 WebSocket 연결 시작");
                retryCount = 0;
                closeLatch.await();
            } catch (Exception e) {
                log.warn("바이낸스 WebSocket 연결 끊김, 재연결 시도 #{}", retryCount + 1, e);
            }
            backoff(retryCount++);
        }
    }

    private void handleMessage(String payload) {
        try {
            BinanceTickerMessage[] tickers = objectMapper.readValue(payload, BinanceTickerMessage[].class);
            for (BinanceTickerMessage ticker : tickers) {
                marketInfoCache.find(Exchange.BINANCE, ticker.symbol())
                        .ifPresent(meta -> tickerSinkProcessor.process(ticker.toNormalized(meta.displayName())));
            }
        } catch (Exception e) {
            log.debug("바이낸스 메시지 처리 실패: {}", e.getMessage());
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

    private class BinanceListener implements WebSocket.Listener {
        private final CountDownLatch closeLatch;
        private final StringBuilder textBuffer = new StringBuilder();

        BinanceListener(CountDownLatch closeLatch) {
            this.closeLatch = closeLatch;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String message = textBuffer.toString();
                textBuffer.setLength(0);
                handleMessage(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("바이낸스 WebSocket 종료: statusCode={}, reason={}", statusCode, reason);
            closeLatch.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("바이낸스 WebSocket 오류", error);
            closeLatch.countDown();
        }
    }
}
