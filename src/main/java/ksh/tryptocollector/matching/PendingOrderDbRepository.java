package ksh.tryptocollector.matching;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingOrderDbRepository {

    private static final String BASE_SQL = """
            SELECT o.order_id
            FROM orders o
            JOIN exchange_coin ec ON o.exchange_coin_id = ec.exchange_coin_id
            JOIN exchange_market em ON ec.exchange_id = em.exchange_id
            JOIN coin c ON ec.coin_id = c.coin_id
            WHERE em.name = ?
              AND c.symbol = ?
              AND o.side = ?
              AND o.status = 'PENDING'
            """;

    private static final String BUY_CONDITION = " AND o.price >= ?";
    private static final String SELL_CONDITION = " AND o.price <= ?";

    private static final String FIND_ALL_PENDING_SQL = """
            SELECT o.order_id, em.name AS exchange, c.symbol AS base,
                   o.side, o.price, o.created_at
            FROM orders o
            JOIN exchange_coin ec ON o.exchange_coin_id = ec.exchange_coin_id
            JOIN exchange_market em ON ec.exchange_id = em.exchange_id
            JOIN coin c ON ec.coin_id = c.coin_id
            WHERE o.status = 'PENDING'
            """;

    private final JdbcTemplate jdbcTemplate;

    public List<Long> findMatchedOrderIds(String exchange, String symbol, String side,
                                          BigDecimal currentPrice) {
        String sql = BASE_SQL + ("BUY".equals(side) ? BUY_CONDITION : SELL_CONDITION);

        return jdbcTemplate.queryForList(sql, Long.class,
                exchange, symbol, side, currentPrice);
    }

    public List<PendingOrder> findAllPendingOrders() {
        return jdbcTemplate.query(FIND_ALL_PENDING_SQL, (rs, rowNum) -> new PendingOrder(
                rs.getLong("order_id"),
                rs.getString("exchange"),
                rs.getString("base"),
                rs.getString("side"),
                rs.getBigDecimal("price"),
                rs.getTimestamp("created_at").toInstant()
        ));
    }
}
