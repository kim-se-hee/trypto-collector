package ksh.tryptocollector.exchange.binance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

@Component
public class BinanceRestClient {
    private final RestClient restClient;
    private final String restUrl;
    private final String candleUrl;

    public BinanceRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${exchange.binance.rest-url}") String restUrl,
            @Value("${exchange.binance.candle-url}") String candleUrl) {
        this.restClient = restClientBuilder.build();
        this.restUrl = restUrl;
        this.candleUrl = candleUrl;
    }

    public List<List<Object>> fetchMinuteCandles(String symbol, long startTime, long endTime, int limit) {
        String url = candleUrl + "?symbol=" + symbol + "&interval=1m"
                + "&startTime=" + startTime + "&endTime=" + endTime + "&limit=" + limit;
        List<List<Object>> responses = restClient.get()
                .uri(url)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (responses == null) {
            return List.of();
        }
        return responses;
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
                .filter(r -> new BigDecimal(r.quoteVolume()).compareTo(BigDecimal.ZERO) > 0)
                .toList();
    }
}
