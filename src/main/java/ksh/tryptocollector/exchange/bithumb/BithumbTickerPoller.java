package ksh.tryptocollector.exchange.bithumb;

import ksh.tryptocollector.exchange.ExchangeTickerPoller;
import ksh.tryptocollector.exchange.NormalizableTicker;
import ksh.tryptocollector.model.Exchange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class BithumbTickerPoller implements ExchangeTickerPoller {
    private final BithumbRestClient bithumbRestClient;

    @Override
    public Exchange exchange() {
        return Exchange.BITHUMB;
    }

    @Override
    public List<? extends NormalizableTicker> fetch(List<String> symbolCodes) {
        return bithumbRestClient.fetchKrwTickers(symbolCodes);
    }
}
