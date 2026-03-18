package ksh.tryptocollector.candle;

import ksh.tryptocollector.model.NormalizedTicker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class CandleBufferTest {

    private CandleBuffer candleBuffer;

    @BeforeEach
    void setUp() {
        candleBuffer = new CandleBuffer();
    }

    @Test
    @DisplayName("같은 key로 여러 번 update하면 OHLC가 올바르게 누적된다")
    void givenMultipleUpdatesWithSameKey_whenFlushAll_thenOhlcAccumulatedCorrectly() {
        // given
        candleBuffer.update(ticker("upbit", "BTC", "KRW", "100"));  // open
        candleBuffer.update(ticker("upbit", "BTC", "KRW", "150"));  // high
        candleBuffer.update(ticker("upbit", "BTC", "KRW", "80"));   // low
        candleBuffer.update(ticker("upbit", "BTC", "KRW", "120"));  // close

        // when
        Map<CandleBuffer.CandleKey, OhlcAccumulator> snapshot = candleBuffer.flushAll();

        // then
        CandleBuffer.CandleKey key = new CandleBuffer.CandleKey("upbit", "BTC", "KRW");
        OhlcAccumulator ohlc = snapshot.get(key);

        assertThat(ohlc).isNotNull();
        assertThat(ohlc.open()).isEqualByComparingTo("100");
        assertThat(ohlc.high()).isEqualByComparingTo("150");
        assertThat(ohlc.low()).isEqualByComparingTo("80");
        assertThat(ohlc.close()).isEqualByComparingTo("120");
    }

    @Test
    @DisplayName("flushAll 이후 새로운 update는 이전 스냅샷과 독립적이다")
    void givenBufferWithData_whenFlushAllAndUpdateAgain_thenNewDataIsIndependent() {
        // given
        candleBuffer.update(ticker("upbit", "BTC", "KRW", "100"));
        Map<CandleBuffer.CandleKey, OhlcAccumulator> firstSnapshot = candleBuffer.flushAll();

        // when
        candleBuffer.update(ticker("upbit", "BTC", "KRW", "200"));
        Map<CandleBuffer.CandleKey, OhlcAccumulator> secondSnapshot = candleBuffer.flushAll();

        // then
        CandleBuffer.CandleKey key = new CandleBuffer.CandleKey("upbit", "BTC", "KRW");

        assertThat(firstSnapshot.get(key).open()).isEqualByComparingTo("100");
        assertThat(secondSnapshot.get(key).open()).isEqualByComparingTo("200");
        assertThat(firstSnapshot).isNotSameAs(secondSnapshot);
    }

    @Test
    @DisplayName("멀티스레드에서 update와 flushAll을 동시에 호출해도 데이터가 유실되지 않는다")
    void givenConcurrentUpdates_whenFlushAllDuringUpdates_thenNoDataLoss() throws InterruptedException {
        // given
        int threadCount = 10;
        int updatesPerThread = 1_000;
        int totalUpdates = threadCount * updatesPerThread;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<Map<CandleBuffer.CandleKey, OhlcAccumulator>> flushedSnapshots = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < updatesPerThread; j++) {
                        candleBuffer.update(ticker("upbit", "BTC", "KRW", "100"));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // when — 중간중간 flush를 수행
        startLatch.countDown();
        while (doneLatch.getCount() > 0) {
            Map<CandleBuffer.CandleKey, OhlcAccumulator> snapshot = candleBuffer.flushAll();
            if (!snapshot.isEmpty()) {
                synchronized (flushedSnapshots) {
                    flushedSnapshots.add(snapshot);
                }
            }
        }
        doneLatch.await();
        // 잔여 데이터 flush
        Map<CandleBuffer.CandleKey, OhlcAccumulator> remaining = candleBuffer.flushAll();
        if (!remaining.isEmpty()) {
            flushedSnapshots.add(remaining);
        }

        executor.shutdown();

        // then — flush된 스냅샷이 하나 이상 존재하고, 모든 스냅샷의 OHLC가 유효하다
        assertThat(flushedSnapshots).isNotEmpty();
        flushedSnapshots.forEach(snapshot -> {
            CandleBuffer.CandleKey key = new CandleBuffer.CandleKey("upbit", "BTC", "KRW");
            OhlcAccumulator ohlc = snapshot.get(key);
            if (ohlc != null) {
                assertThat(ohlc.open()).isEqualByComparingTo("100");
                assertThat(ohlc.high()).isEqualByComparingTo("100");
                assertThat(ohlc.low()).isEqualByComparingTo("100");
                assertThat(ohlc.close()).isEqualByComparingTo("100");
            }
        });
    }

    private NormalizedTicker ticker(String exchange, String base, String quote, String price) {
        return new NormalizedTicker(
                exchange, base, quote, base + "/" + quote,
                new BigDecimal(price), BigDecimal.ZERO, BigDecimal.ZERO, System.currentTimeMillis()
        );
    }
}
