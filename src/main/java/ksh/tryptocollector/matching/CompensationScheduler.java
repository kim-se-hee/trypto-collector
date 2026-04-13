package ksh.tryptocollector.matching;

import ksh.tryptocollector.config.LeaderElection;
import ksh.tryptocollector.model.Exchange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompensationScheduler {

    private static final long SCHEDULE_DELAY_MS = 10_000;

    private final PendingOrderDbRepository pendingOrderDbRepository;
    private final PendingOrderRedisRepository pendingOrderRedisRepository;
    private final MatchedOrderPublisher matchedOrderPublisher;
    private final TickRawRepository tickRawRepository;
    private final LeaderElection leaderElection;

    @Scheduled(fixedDelay = SCHEDULE_DELAY_MS)
    public void compensate() {
        if (!leaderElection.isLeader()) {
            return;
        }
        List<PendingOrder> orders = pendingOrderDbRepository.findAllPendingOrders();

        if (orders.isEmpty()) return;

        for (PendingOrder order : orders) {
            try {
                String quote = Exchange.valueOf(order.exchange()).getQuote();
                String symbol = order.base() + "/" + quote;

                Optional<BigDecimal> tickPrice = tickRawRepository.findFirstMatchingPrice(
                        order.exchange(), symbol, order.side(), order.price(), order.createdAt());

                if (tickPrice.isPresent()) {
                    publishMatch(order, tickPrice.get());
                } else {
                    addToRedisZSet(order, symbol);
                }
            } catch (Exception e) {
                log.warn("보상 처리 실패: orderId={}", order.orderId(), e);
            }
        }
    }

    private void publishMatch(PendingOrder order, BigDecimal filledPrice) {
        var item = new MatchedOrderMessage.Item(order.orderId(), filledPrice);
        matchedOrderPublisher.publish(new MatchedOrderMessage(List.of(item)));
    }

    private void addToRedisZSet(PendingOrder order, String symbol) {
        pendingOrderRedisRepository.add(
                order.exchange(), symbol, order.side(),
                String.valueOf(order.orderId()), order.price().doubleValue());
    }
}
