package ksh.tryptocollector.matching;

import ksh.tryptocollector.metadata.ExchangeInitializer;
import ksh.tryptocollector.support.TestContainerConfiguration;
import ksh.tryptocollector.support.TestInfluxConfig;
import ksh.tryptocollector.support.TestRedissonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestContainerConfiguration.class, TestRedissonConfig.class, TestInfluxConfig.class})
@DisplayName("PendingOrderWarmupService DB→Redis 웜업 통합 테스트")
class PendingOrderWarmupServiceIntegrationTest {

    @Autowired
    private PendingOrderWarmupService warmupService;

    @Autowired
    private PendingOrderRedisRepository pendingOrderRedisRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private ExchangeInitializer exchangeInitializer;

    @MockitoBean
    private CompensationScheduler compensationScheduler;

    @BeforeEach
    void cleanup() {
        jdbcTemplate.execute("DELETE FROM orders");
        jdbcTemplate.execute("DELETE FROM exchange_coin");
        jdbcTemplate.execute("DELETE FROM coin");
        jdbcTemplate.execute("DELETE FROM exchange_market");

        Set<String> keys = redisTemplate.keys("pending:orders:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private long insertExchange(String name) {
        jdbcTemplate.update("INSERT INTO exchange_market(name) VALUES (?)", name);
        return jdbcTemplate.queryForObject(
                "SELECT exchange_id FROM exchange_market WHERE name = ?", Long.class, name);
    }

    private long insertCoin(String symbol) {
        jdbcTemplate.update("INSERT INTO coin(symbol) VALUES (?)", symbol);
        return jdbcTemplate.queryForObject(
                "SELECT coin_id FROM coin WHERE symbol = ?", Long.class, symbol);
    }

    private long insertExchangeCoin(long exchangeId, long coinId) {
        jdbcTemplate.update("INSERT INTO exchange_coin(exchange_id, coin_id) VALUES (?, ?)",
                exchangeId, coinId);
        return jdbcTemplate.queryForObject(
                "SELECT exchange_coin_id FROM exchange_coin WHERE exchange_id = ? AND coin_id = ?",
                Long.class, exchangeId, coinId);
    }

    private void insertOrder(long exchangeCoinId, String side, String status, String price) {
        jdbcTemplate.update(
                "INSERT INTO orders(exchange_coin_id, side, status, price) VALUES (?, ?, ?, ?)",
                exchangeCoinId, side, status, new BigDecimal(price));
    }

    @Test
    @DisplayName("PENDING 주문 모두를 Redis ZSet에 (exchange, base/quote, side) 키로 적재한다")
    void loadsAllPendingOrdersIntoRedisZSetWithCorrectKeys() {
        long upbitId = insertExchange("UPBIT");
        long binanceId = insertExchange("BINANCE");
        long btcId = insertCoin("BTC");
        long ethId = insertCoin("ETH");

        long upbitBtcEcId = insertExchangeCoin(upbitId, btcId);
        long upbitEthEcId = insertExchangeCoin(upbitId, ethId);
        long binanceBtcEcId = insertExchangeCoin(binanceId, btcId);

        insertOrder(upbitBtcEcId, "BUY", "PENDING", "50000000");
        insertOrder(upbitBtcEcId, "SELL", "PENDING", "52000000");
        insertOrder(upbitEthEcId, "BUY", "PENDING", "3000000");
        insertOrder(binanceBtcEcId, "BUY", "PENDING", "35000");

        // FILLED는 웜업 대상이 아님
        insertOrder(upbitBtcEcId, "BUY", "FILLED", "48000000");

        warmupService.warmup();

        assertThat(pendingOrderRedisRepository.findMatchedBuyOrders(
                "UPBIT", "BTC/KRW", new BigDecimal("50000000")))
                .hasSize(1);

        assertThat(pendingOrderRedisRepository.findMatchedSellOrders(
                "UPBIT", "BTC/KRW", new BigDecimal("52000000")))
                .hasSize(1);

        assertThat(pendingOrderRedisRepository.findMatchedBuyOrders(
                "UPBIT", "ETH/KRW", new BigDecimal("3000000")))
                .hasSize(1);

        assertThat(pendingOrderRedisRepository.findMatchedBuyOrders(
                "BINANCE", "BTC/USDT", new BigDecimal("35000")))
                .hasSize(1);

        // FILLED 주문은 ZSet에 없어야 한다
        assertThat(pendingOrderRedisRepository.findMatchedBuyOrders(
                "UPBIT", "BTC/KRW", new BigDecimal("48000000")))
                .hasSize(1); // 50M price order matches when current >= 48M; only the 50M pending order
    }

    @Test
    @DisplayName("PENDING 주문이 하나도 없으면 아무것도 적재하지 않는다")
    void skipsWarmupWhenNoPendingOrders() {
        long upbitId = insertExchange("UPBIT");
        long btcId = insertCoin("BTC");
        long upbitBtcEcId = insertExchangeCoin(upbitId, btcId);
        insertOrder(upbitBtcEcId, "BUY", "FILLED", "50000000");

        warmupService.warmup();

        assertThat(pendingOrderRedisRepository.findMatchedBuyOrders(
                "UPBIT", "BTC/KRW", new BigDecimal("999999999"))).isEmpty();
    }
}
