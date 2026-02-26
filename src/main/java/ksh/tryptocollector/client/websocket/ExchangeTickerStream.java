package ksh.tryptocollector.client.websocket;

import reactor.core.publisher.Mono;

public interface ExchangeTickerStream {

    Mono<Void> connect();
}
