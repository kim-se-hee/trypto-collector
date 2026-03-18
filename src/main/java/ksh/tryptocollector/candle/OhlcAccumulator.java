package ksh.tryptocollector.candle;

import java.math.BigDecimal;

public record OhlcAccumulator(
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close
) {
    static OhlcAccumulator init(BigDecimal price) {
        return new OhlcAccumulator(price, price, price, price);
    }

    OhlcAccumulator update(BigDecimal price) {
        return new OhlcAccumulator(
                open,
                high.max(price),
                low.min(price),
                price
        );
    }
}
