package ksh.tryptocollector.matching;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import ksh.tryptocollector.model.NormalizedTicker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingOrderMatcher {

    private final PendingOrderRedisRepository pendingOrderRedisRepository;
    private final MatchedOrderPublisher matchedOrderPublisher;
    private final MeterRegistry meterRegistry;

    public void match(NormalizedTicker ticker) {
        String exchange = ticker.exchange();
        String symbol = ticker.base() + "/" + ticker.quote();

        Set<String> buyOrderIds = pendingOrderRedisRepository
                .findMatchedBuyOrders(exchange, symbol, ticker.lastPrice());
        Set<String> sellOrderIds = pendingOrderRedisRepository
                .findMatchedSellOrders(exchange, symbol, ticker.lastPrice());

        if (buyOrderIds.isEmpty() && sellOrderIds.isEmpty()) {
            return;
        }

        List<MatchedOrderMessage.Item> items = new ArrayList<>();
        for (String orderId : buyOrderIds) {
            items.add(new MatchedOrderMessage.Item(Long.valueOf(orderId), ticker.lastPrice()));
        }
        for (String orderId : sellOrderIds) {
            items.add(new MatchedOrderMessage.Item(Long.valueOf(orderId), ticker.lastPrice()));
        }

        MatchedOrderMessage message = new MatchedOrderMessage(items);
        boolean acked = matchedOrderPublisher.publish(message);

        if (acked) {
            pendingOrderRedisRepository.removeAll(exchange, symbol, "BUY", buyOrderIds);
            pendingOrderRedisRepository.removeAll(exchange, symbol, "SELL", sellOrderIds);
            log.info("주문 매칭 완료: {}/{} {}건", exchange, symbol, items.size());
        } else {
            log.warn("주문 매칭 발행 실패, ZREM 생략: {}/{} {}건", exchange, symbol, items.size());
        }

        Counter.builder("matching.matched.count")
                .tag("exchange", exchange)
                .tag("acked", String.valueOf(acked))
                .register(meterRegistry)
                .increment(items.size());
    }
}
