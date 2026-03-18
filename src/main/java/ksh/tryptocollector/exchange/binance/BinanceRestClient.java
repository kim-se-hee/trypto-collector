package ksh.tryptocollector.exchange.binance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;

@Component
public class BinanceRestClient {
    private final RestClient restClient;
    private final String restUrl;

    public BinanceRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${exchange.binance.rest-url}") String restUrl) {
        this.restClient = restClientBuilder.build();
        this.restUrl = restUrl;
    }

    public List<BinanceTickerResponse> fetchUsdtTickers() {
        BinanceTickerResponse[] responses = restClient.get()
                .uri(restUrl)
                .retrieve()
                .body(BinanceTickerResponse[].class);
        if (responses == null) {
            return List.of();
        }
        return Arrays.stream(responses)
                .filter(r -> r.symbol().endsWith("USDT"))
                .toList();
    }
}
