package ksh.tryptocollector.client.websocket.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ksh.tryptocollector.common.model.Exchange;
import ksh.tryptocollector.common.model.NormalizedTicker;

import java.math.BigDecimal;

public record BithumbTickerMessage(
        String code,
        @JsonProperty("trade_price") BigDecimal tradePrice,
        @JsonProperty("signed_change_rate") BigDecimal signedChangeRate,
        @JsonProperty("acc_trade_price_24h") BigDecimal accTradePrice24h,
        long timestamp
) {

    public NormalizedTicker toNormalized(String displayName) {
        String base = code.substring(4);
        return new NormalizedTicker(
                Exchange.BITHUMB.name(),
                base, "KRW", displayName,
                tradePrice,
                signedChangeRate,
                accTradePrice24h,
                System.currentTimeMillis()
        );
    }
}
