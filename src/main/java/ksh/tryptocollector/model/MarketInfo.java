package ksh.tryptocollector.model;

public record MarketInfo(
        String base,
        String quote,
        String pair,
        String displayName
) {
}
