package ksh.tryptocollector.exchange.upbit;

import com.fasterxml.jackson.annotation.JsonProperty;
import ksh.tryptocollector.exchange.NormalizableTicker;
import ksh.tryptocollector.model.Exchange;
import ksh.tryptocollector.model.NormalizedTicker;

import java.math.BigDecimal;

public record UpbitTickerResponse(
        String market,
        @JsonProperty("trade_price") BigDecimal tradePrice,
        @JsonProperty("signed_change_rate") BigDecimal signedChangeRate,
        @JsonProperty("acc_trade_price_24h") BigDecimal accTradePrice24h,
        long timestamp
) implements NormalizableTicker {
    @Override
    public String code() {
        return market;
    }

    @Override
    public NormalizedTicker toNormalized(String displayName) {
        String base = market.substring(4);
        return new NormalizedTicker(
                Exchange.UPBIT.name(),
                base, "KRW", displayName,
                tradePrice,
                signedChangeRate,
                accTradePrice24h,
                System.currentTimeMillis()
        );
    }
}
