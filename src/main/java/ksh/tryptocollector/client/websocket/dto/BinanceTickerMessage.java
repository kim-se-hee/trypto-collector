package ksh.tryptocollector.client.websocket.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ksh.tryptocollector.common.model.Exchange;
import ksh.tryptocollector.common.model.NormalizedTicker;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record BinanceTickerMessage(
        @JsonProperty("s") String symbol,
        @JsonProperty("c") String lastPrice,
        @JsonProperty("o") String openPrice,
        @JsonProperty("q") String quoteVolume,
        @JsonProperty("E") long eventTime
) {

    private static final int CHANGE_RATE_SCALE = 8;

    public NormalizedTicker toNormalized(String displayName) {
        String base = symbol.replace("USDT", "");
        BigDecimal close = new BigDecimal(lastPrice);
        BigDecimal open = new BigDecimal(openPrice);
        BigDecimal changeRate = BigDecimal.ZERO;
        if (open.compareTo(BigDecimal.ZERO) != 0) {
            changeRate = close.subtract(open)
                    .divide(open, CHANGE_RATE_SCALE, RoundingMode.HALF_UP);
        }
        return new NormalizedTicker(
                Exchange.BINANCE.name(),
                base, "USDT", displayName,
                close,
                changeRate,
                new BigDecimal(quoteVolume),
                System.currentTimeMillis()
        );
    }
}
