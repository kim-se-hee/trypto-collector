package ksh.tryptocollector.common.model;

import java.math.BigDecimal;

public record TickerEvent(
        String exchange,
        String symbol,
        BigDecimal currentPrice,
        long timestamp
) {

    public static TickerEvent from(NormalizedTicker ticker) {
        return new TickerEvent(
                ticker.exchange(),
                ticker.base() + "/" + ticker.quote(),
                ticker.lastPrice(),
                ticker.tsMs()
        );
    }
}
