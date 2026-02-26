package ksh.tryptocollector.client.rest;

import ksh.tryptocollector.client.rest.dto.BinanceTickerResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@Component
public class BinanceRestClient {

    private final WebClient webClient;
    private final String restUrl;

    public BinanceRestClient(
            WebClient webClient,
            @Value("${exchange.binance.rest-url}") String restUrl) {
        this.webClient = webClient;
        this.restUrl = restUrl;
    }

    public Flux<BinanceTickerResponse> fetchUsdtTickers() {
        return webClient.get()
                .uri(restUrl)
                .retrieve()
                .bodyToFlux(BinanceTickerResponse.class)
                .filter(r -> r.symbol().endsWith("USDT"));
    }
}
