package ksh.tryptocollector.exchange.upbit;

import ksh.tryptocollector.exchange.ExchangeTickerPoller;
import ksh.tryptocollector.exchange.NormalizableTicker;
import ksh.tryptocollector.model.Exchange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class UpbitTickerPoller implements ExchangeTickerPoller {
    private final UpbitRestClient upbitRestClient;

    @Override
    public Exchange exchange() {
        return Exchange.UPBIT;
    }

    @Override
    public List<? extends NormalizableTicker> fetch(List<String> symbolCodes) {
        return upbitRestClient.fetchKrwTickers(symbolCodes);
    }
}
