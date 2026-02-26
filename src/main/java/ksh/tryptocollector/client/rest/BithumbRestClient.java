package ksh.tryptocollector.client.rest;

import ksh.tryptocollector.client.rest.dto.BithumbMarketResponse;
import ksh.tryptocollector.metadata.model.MarketInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@Component
public class BithumbRestClient {

    private final WebClient webClient;
    private final String restUrl;

    public BithumbRestClient(
            WebClient webClient,
            @Value("${exchange.bithumb.rest-url}") String restUrl) {
        this.webClient = webClient;
        this.restUrl = restUrl;
    }

    public Flux<MarketInfo> fetchKrwMarkets() {
        return webClient.get()
                .uri(restUrl)
                .retrieve()
                .bodyToFlux(BithumbMarketResponse.class)
                .filter(r -> r.market().startsWith("KRW-"))
                .map(r -> {
                    String base = r.market().substring(4);
                    return new MarketInfo(base, "KRW", base + "/KRW", r.koreanName());
                });
    }
}
