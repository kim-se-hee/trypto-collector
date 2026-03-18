package ksh.tryptocollector.candle;

import ksh.tryptocollector.model.NormalizedTicker;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class CandleBuffer {
    private final AtomicReference<ConcurrentHashMap<CandleKey, OhlcAccumulator>> bufferRef =
            new AtomicReference<>(new ConcurrentHashMap<>());

    public void update(NormalizedTicker ticker) {
        CandleKey key = new CandleKey(ticker.exchange(), ticker.base(), ticker.quote());
        OhlcAccumulator initial = OhlcAccumulator.init(ticker.lastPrice());
        bufferRef.get().merge(key, initial, (existing, v) -> existing.update(v.close()));
    }

    public Map<CandleKey, OhlcAccumulator> flushAll() {
        return bufferRef.getAndSet(new ConcurrentHashMap<>());
    }

    public record CandleKey(String exchange, String base, String quote) {
        public String coin() {
            return base + "/" + quote;
        }
    }

}
