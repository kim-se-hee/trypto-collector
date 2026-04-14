package ksh.tryptocollector.matching;

import java.math.BigDecimal;
import java.util.List;

public record MatchedOrderMessage(
        long tickerTsMs,
        long publishedAtMs,
        List<Item> matched
) {

    public record Item(
            Long orderId,
            BigDecimal filledPrice
    ) {}
}
