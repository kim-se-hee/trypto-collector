package ksh.tryptocollector.exchange.upbit;

import ksh.tryptocollector.model.MarketInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;

@Component
public class UpbitRestClient {
    private final RestClient restClient;
    private final String restUrl;

    public UpbitRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${exchange.upbit.rest-url}") String restUrl) {
        this.restClient = restClientBuilder.build();
        this.restUrl = restUrl;
    }

    public List<MarketInfo> fetchKrwMarkets() {
        UpbitMarketResponse[] responses = restClient.get()
                .uri(restUrl)
                .retrieve()
                .body(UpbitMarketResponse[].class);
        if (responses == null) {
            return List.of();
        }
        return Arrays.stream(responses)
                .filter(r -> r.market().startsWith("KRW-"))
                .map(r -> {
                    String base = r.market().substring(4);
                    return new MarketInfo(base, "KRW", base + "/KRW", r.koreanName());
                })
                .toList();
    }
}
