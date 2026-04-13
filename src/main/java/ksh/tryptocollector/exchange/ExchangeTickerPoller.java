package ksh.tryptocollector.exchange;

import ksh.tryptocollector.model.Exchange;

import java.util.List;

public interface ExchangeTickerPoller {
    Exchange exchange();

    List<? extends NormalizableTicker> fetch(List<String> symbolCodes);
}
