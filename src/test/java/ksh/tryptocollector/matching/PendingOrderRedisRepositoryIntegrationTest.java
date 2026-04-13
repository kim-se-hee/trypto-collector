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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestContainerConfiguration.class, TestRedissonConfig.class, TestInfluxConfig.class})
@DisplayName("PendingOrderRedisRepository ZSet 매칭 통합 테스트")
class PendingOrderRedisRepositoryIntegrationTest {

    @Autowired
    private PendingOrderRedisRepository repository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private ExchangeInitializer exchangeInitializer;

    @MockitoBean
    private CompensationScheduler compensationScheduler;

    private static final String EXCHANGE = "UPBIT";
    private static final String SYMBOL = "BTC/KRW";

    @BeforeEach
    void cleanup() {
        Set<String> keys = redisTemplate.keys("pending:orders:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("BUY 주문은 orderPrice가 currentPrice 이상인 주문만 매칭된다 (시장가가 리밋 이하로 내려올 때 체결)")
    void buyOrderMatchesWhenOrderPriceAtOrAboveCurrentPrice() {
        repository.add(EXCHANGE, SYMBOL, "BUY", "1", 50_000_000d);
        repository.add(EXCHANGE, SYMBOL, "BUY", "2", 51_000_000d);
        repository.add(EXCHANGE, SYMBOL, "BUY", "3", 52_000_000d);

        // currentPrice 51,000,000 → order 2, 3 매칭 (orderPrice >= currentPrice)
        Set<String> matched = repository.findMatchedBuyOrders(EXCHANGE, SYMBOL, new BigDecimal("51000000"));

        assertThat(matched).containsExactlyInAnyOrder("2", "3");
    }

    @Test
    @DisplayName("SELL 주문은 orderPrice가 currentPrice 이하인 주문만 매칭된다 (시장가가 리밋 이상으로 올라갈 때 체결)")
    void sellOrderMatchesWhenOrderPriceAtOrBelowCurrentPrice() {
        repository.add(EXCHANGE, SYMBOL, "SELL", "10", 49_000_000d);
        repository.add(EXCHANGE, SYMBOL, "SELL", "11", 50_000_000d);
        repository.add(EXCHANGE, SYMBOL, "SELL", "12", 51_000_000d);

        // currentPrice 50,000,000 → order 10, 11 매칭 (orderPrice <= currentPrice)
        Set<String> matched = repository.findMatchedSellOrders(EXCHANGE, SYMBOL, new BigDecimal("50000000"));

        assertThat(matched).containsExactlyInAnyOrder("10", "11");
    }

    @Test
    @DisplayName("매칭 결과가 없으면 빈 Set을 반환한다")
    void returnsEmptySetWhenNoOrdersMatch() {
        repository.add(EXCHANGE, SYMBOL, "BUY", "1", 50_000_000d);

        // BUY 50M + currentPrice 60M → 50M < 60M 이므로 매칭 없음
        Set<String> matched = repository.findMatchedBuyOrders(EXCHANGE, SYMBOL, new BigDecimal("60000000"));

        assertThat(matched).isEmpty();
    }

    @Test
    @DisplayName("removeAll은 전달된 모든 주문ID를 ZSet에서 제거한다")
    void removeAllRemovesAllProvidedOrderIdsFromZSet() {
        repository.add(EXCHANGE, SYMBOL, "BUY", "1", 50_000_000d);
        repository.add(EXCHANGE, SYMBOL, "BUY", "2", 51_000_000d);
        repository.add(EXCHANGE, SYMBOL, "BUY", "3", 52_000_000d);

        repository.removeAll(EXCHANGE, SYMBOL, "BUY", Set.of("1", "2"));

        Set<String> remaining = repository.findMatchedBuyOrders(EXCHANGE, SYMBOL, new BigDecimal("0"));
        assertThat(remaining).containsExactly("3");
    }

    @Test
    @DisplayName("remove는 단일 주문을 ZSet에서 제거한다")
    void removeRemovesSingleOrderFromZSet() {
        repository.add(EXCHANGE, SYMBOL, "SELL", "1", 50_000_000d);
        repository.add(EXCHANGE, SYMBOL, "SELL", "2", 51_000_000d);

        repository.remove(EXCHANGE, SYMBOL, "SELL", "1");

        Set<String> remaining = repository.findMatchedSellOrders(EXCHANGE, SYMBOL, new BigDecimal("999000000"));
        assertThat(remaining).containsExactly("2");
    }

    @Test
    @DisplayName("빈 orderIds로 removeAll 호출 시 예외 없이 종료된다")
    void removeAllWithEmptySetIsSafeNoOp() {
        repository.add(EXCHANGE, SYMBOL, "BUY", "1", 50_000_000d);

        repository.removeAll(EXCHANGE, SYMBOL, "BUY", Set.of());

        Set<String> remaining = repository.findMatchedBuyOrders(EXCHANGE, SYMBOL, new BigDecimal("0"));
        assertThat(remaining).containsExactly("1");
    }

    @Test
    @DisplayName("exchange별 symbol별 side별로 키가 분리된다")
    void keysAreIsolatedByExchangeSymbolSide() {
        repository.add("UPBIT", "BTC/KRW", "BUY", "1", 50_000_000d);
        repository.add("BINANCE", "BTC/USDT", "BUY", "2", 50_000_000d);
        repository.add("UPBIT", "BTC/KRW", "SELL", "3", 50_000_000d);

        Set<String> upbitBtcBuys = repository.findMatchedBuyOrders("UPBIT", "BTC/KRW", new BigDecimal("50000000"));
        Set<String> binanceBtcBuys = repository.findMatchedBuyOrders("BINANCE", "BTC/USDT", new BigDecimal("50000000"));
        Set<String> upbitBtcSells = repository.findMatchedSellOrders("UPBIT", "BTC/KRW", new BigDecimal("50000000"));

        assertThat(upbitBtcBuys).containsExactly("1");
        assertThat(binanceBtcBuys).containsExactly("2");
        assertThat(upbitBtcSells).containsExactly("3");
    }
}
