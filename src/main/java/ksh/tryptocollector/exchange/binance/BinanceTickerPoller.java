package ksh.tryptocollector.exchange.binance;

import ksh.tryptocollector.exchange.ExchangeTickerPoller;
import ksh.tryptocollector.exchange.NormalizableTicker;
import ksh.tryptocollector.model.Exchange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class BinanceTickerPoller implements ExchangeTickerPoller {
    private final BinanceRestClient binanceRestClient;

    @Override
    public Exchange exchange() {
        return Exchange.BINANCE;
    }

    @Override
    public List<? extends NormalizableTicker> fetch(List<String> symbolCodes) {
        return binanceRestClient.fetchUsdtTickers().stream()
                .filter(r -> symbolCodes.contains(r.symbol()))
                .toList();
    }
}
