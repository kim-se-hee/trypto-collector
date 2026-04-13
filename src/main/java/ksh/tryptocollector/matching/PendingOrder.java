package ksh.tryptocollector.matching;

import java.math.BigDecimal;
import java.time.Instant;

record PendingOrder(
        Long orderId,
        String exchange,
        String base,
        String side,
        BigDecimal price,
        Instant createdAt
) {}
