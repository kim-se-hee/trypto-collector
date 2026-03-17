package ksh.tryptocollector.exchange;

import reactor.core.publisher.Mono;

public interface ExchangeTickerStream {
    Mono<Void> connect();
}
