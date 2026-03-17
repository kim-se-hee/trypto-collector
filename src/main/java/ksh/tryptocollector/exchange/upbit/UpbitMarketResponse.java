package ksh.tryptocollector.exchange.upbit;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpbitMarketResponse(
        String market,
        @JsonProperty("korean_name") String koreanName,
        @JsonProperty("english_name") String englishName
) {
}
