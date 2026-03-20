package ksh.tryptocollector.exchange.bithumb;

import java.util.List;

public record BithumbCandleResponse(
        String status,
        List<List<Object>> data
) {
}
