package ksh.tryptocollector.metadata.model;

public record MarketInfo(
        String base,
        String quote,
        String pair,
        String displayName
) {
}
