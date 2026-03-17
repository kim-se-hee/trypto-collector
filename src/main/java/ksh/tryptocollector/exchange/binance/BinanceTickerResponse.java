package ksh.tryptocollector.exchange.binance;

public record BinanceTickerResponse(
        String symbol,
        String lastPrice,
        String priceChangePercent,
        String quoteVolume
) {
}
