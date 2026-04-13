package ksh.tryptocollector.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Exchange {

    UPBIT("KRW"),
    BITHUMB("KRW"),
    BINANCE("USDT");

    private final String quote;
}
