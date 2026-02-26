package ksh.tryptocollector.client.rest;

import ksh.tryptocollector.client.rest.dto.UpbitMarketResponse;
import ksh.tryptocollector.metadata.model.MarketInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@Component
public class UpbitRestClient {

    private final WebClient webClient;
    private final String restUrl;

    public UpbitRestClient(
            WebClient webClient,
            @Value("${exchange.upbit.rest-url}") String restUrl) {
        this.webClient = webClient;
        this.restUrl = restUrl;
    }

    public Flux<MarketInfo> fetchKrwMarkets() {
        return webClient.get()
                .uri(restUrl)
                .retrieve()
                .bodyToFlux(UpbitMarketResponse.class)
                .filter(r -> r.market().startsWith("KRW-"))
                .map(r -> {
                    String base = r.market().substring(4);
                    return new MarketInfo(base, "KRW", base + "/KRW", r.koreanName());
                });
    }
}
