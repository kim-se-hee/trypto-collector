package ksh.tryptocollector.matching;

import ksh.tryptocollector.model.Exchange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingOrderWarmupService {

    private final PendingOrderDbRepository pendingOrderDbRepository;
    private final PendingOrderRedisRepository pendingOrderRedisRepository;

    public void warmup() {
        List<PendingOrder> orders = pendingOrderDbRepository.findAllPendingOrders();

        if (orders.isEmpty()) {
            log.info("PENDING 주문 없음, 웜업 스킵");
            return;
        }

        int success = 0;
        for (PendingOrder order : orders) {
            try {
                String quote = Exchange.valueOf(order.exchange()).getQuote();
                String symbol = order.base() + "/" + quote;
                pendingOrderRedisRepository.add(
                        order.exchange(), symbol, order.side(),
                        String.valueOf(order.orderId()), order.price().doubleValue());
                success++;
            } catch (Exception e) {
                log.warn("웜업 실패: orderId={}", order.orderId(), e);
            }
        }
        log.info("PENDING 주문 웜업 완료: {}/{}건", success, orders.size());
    }
}
