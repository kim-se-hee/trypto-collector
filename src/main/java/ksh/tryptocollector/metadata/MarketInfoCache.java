package ksh.tryptocollector.metadata;

import ksh.tryptocollector.model.Exchange;
import ksh.tryptocollector.model.MarketInfo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MarketInfoCache {

    private final ConcurrentHashMap<String, MarketInfo> cache = new ConcurrentHashMap<>();

    public void put(Exchange exchange, String symbolCode, MarketInfo marketInfo) {
        cache.put(buildKey(exchange, symbolCode), marketInfo);
    }

    public Optional<MarketInfo> find(Exchange exchange, String symbolCode) {
        return Optional.ofNullable(cache.get(buildKey(exchange, symbolCode)));
    }

    public List<String> getSymbolCodes(Exchange exchange) {
        String prefix = exchange.name() + ":";
        return cache.keySet().stream()
                .filter(key -> key.startsWith(prefix))
                .map(key -> key.substring(prefix.length()))
                .toList();
    }

    public List<MarketInfo> getMarketInfos(Exchange exchange) {
        String prefix = exchange.name() + ":";
        return cache.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(java.util.Map.Entry::getValue)
                .toList();
    }

    public void clear(Exchange exchange) {
        String prefix = exchange.name() + ":";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private String buildKey(Exchange exchange, String symbolCode) {
        return exchange.name() + ":" + symbolCode;
    }
}
