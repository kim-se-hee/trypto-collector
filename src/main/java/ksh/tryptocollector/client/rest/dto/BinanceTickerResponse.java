package ksh.tryptocollector.client.rest.dto;

public record BinanceTickerResponse(
        String symbol,
        String lastPrice,
        String priceChangePercent,
        String quoteVolume
) {
}
