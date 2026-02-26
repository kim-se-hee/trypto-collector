package ksh.tryptocollector.common.model;

import java.math.BigDecimal;

public record NormalizedTicker(
        String exchange,
        String base,
        String quote,
        String displayName,
        BigDecimal lastPrice,
        BigDecimal changeRate,
        BigDecimal quoteTurnover,
        long tsMs
) {
}
