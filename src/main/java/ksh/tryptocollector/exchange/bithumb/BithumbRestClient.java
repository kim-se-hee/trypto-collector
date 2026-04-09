package ksh.tryptocollector.exchange.bithumb;

import ksh.tryptocollector.model.MarketInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;


@Component
public class BithumbRestClient {
    private final RestClient restClient;
    private final String restUrl;
    private final String tickerUrl;

    public BithumbRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${exchange.bithumb.rest-url}") String restUrl,
            @Value("${exchange.bithumb.ticker-url}") String tickerUrl) {
        this.restClient = restClientBuilder.build();
        this.restUrl = restUrl;
        this.tickerUrl = tickerUrl;
    }

    public List<MarketInfo> fetchKrwMarkets() {
        BithumbMarketResponse[] responses = restClient.get()
                .uri(restUrl)
                .retrieve()
                .body(BithumbMarketResponse[].class);
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

    public List<BithumbTickerResponse> fetchKrwTickers(List<String> marketCodes) {
        String markets = String.join(",", marketCodes);
        BithumbTickerResponse[] responses = restClient.get()
                .uri(tickerUrl + "?markets=" + markets)
                .retrieve()
                .body(BithumbTickerResponse[].class);
        if (responses == null) {
            return List.of();
        }
        return Arrays.asList(responses);
    }
}
