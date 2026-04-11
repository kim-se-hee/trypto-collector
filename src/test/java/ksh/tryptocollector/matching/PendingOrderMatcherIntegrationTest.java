package ksh.tryptocollector.matching;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import ksh.tryptocollector.metadata.ExchangeInitializer;
import ksh.tryptocollector.model.NormalizedTicker;
import ksh.tryptocollector.support.TestContainerConfiguration;
import ksh.tryptocollector.support.TestInfluxConfig;
import ksh.tryptocollector.support.TestRedissonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestContainerConfiguration.class, TestRedissonConfig.class, TestInfluxConfig.class})
@DisplayName("PendingOrderMatcher Redis/DB 매칭 통합 테스트")
class PendingOrderMatcherIntegrationTest {

    @Autowired
    private PendingOrderMatcher matcher;

    @Autowired
    private PendingOrderRedisRepository redisRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CircuitBreaker redisCircuitBreaker;

    @MockitoBean
    private PendingOrderDbRepository pendingOrderDbRepository;

    @MockitoBean
    private MatchedOrderPublisher matchedOrderPublisher;

    @MockitoBean
    private ExchangeInitializer exchangeInitializer;

    @MockitoBean
    private CompensationScheduler compensationScheduler;

    @BeforeEach
    void cleanup() {
        Set<String> keys = redisTemplate.keys("pending:orders:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        redisCircuitBreaker.reset();
    }

    private NormalizedTicker ticker(String price) {
        return new NormalizedTicker(
                "UPBIT", "BTC", "KRW", "비트코인",
                new BigDecimal(price), BigDecimal.ZERO,
                new BigDecimal("1000000"), System.currentTimeMillis()
        );
    }

    @Test
    @DisplayName("Redis ZSet에 매칭되는 BUY/SELL 주문이 있으면 publish 후 ZREM한다")
    void givenMatchingOrders_whenMatch_thenPublishAndRemoveFromRedis() {
        // given
        redisRepository.add("UPBIT", "BTC/KRW", "BUY", "100", 50_000_000d);
        redisRepository.add("UPBIT", "BTC/KRW", "BUY", "101", 51_000_000d);
        redisRepository.add("UPBIT", "BTC/KRW", "SELL", "200", 49_000_000d);
        given(matchedOrderPublisher.publish(any(MatchedOrderMessage.class))).willReturn(true);

        // when — currentPrice = 50,500,000
        // BUY 100 (50M): 50M >= 50.5M? NO → 미매칭
        // BUY 101 (51M): 51M >= 50.5M? YES → 매칭
        // SELL 200 (49M): 49M <= 50.5M? YES → 매칭
        matcher.match(ticker("50500000"));

        // then
        ArgumentCaptor<MatchedOrderMessage> captor = ArgumentCaptor.forClass(MatchedOrderMessage.class);
        verify(matchedOrderPublisher).publish(captor.capture());
        List<Long> matchedIds = captor.getValue().matched().stream()
                .map(MatchedOrderMessage.Item::orderId)
                .toList();
        assertThat(matchedIds).containsExactlyInAnyOrder(101L, 200L);

        // BUY 101 체결되어 ZREM → BUY 100만 남음
        Set<String> remainingBuys = redisRepository.findMatchedBuyOrders(
                "UPBIT", "BTC/KRW", new BigDecimal("0"));
        assertThat(remainingBuys).containsExactly("100");
    }

    @Test
    @DisplayName("publish가 실패(nack)하면 Redis에서 주문을 제거하지 않는다")
    void givenPublishFails_whenMatch_thenDoNotRemoveFromRedis() {
        // given
        redisRepository.add("UPBIT", "BTC/KRW", "BUY", "100", 50_000_000d);
        given(matchedOrderPublisher.publish(any(MatchedOrderMessage.class))).willReturn(false);

        // when
        matcher.match(ticker("50000000"));

        // then
        verify(matchedOrderPublisher).publish(any(MatchedOrderMessage.class));
        Set<String> remaining = redisRepository.findMatchedBuyOrders(
                "UPBIT", "BTC/KRW", new BigDecimal("0"));
        assertThat(remaining).containsExactly("100");
    }

    @Test
    @DisplayName("매칭되는 주문이 없으면 publish를 호출하지 않는다")
    void givenNoMatches_whenMatch_thenDoNotPublish() {
        // given
        redisRepository.add("UPBIT", "BTC/KRW", "BUY", "100", 50_000_000d);

        // when — currentPrice = 60,000,000 → BUY 50M < 60M 이므로 매칭 없음
        matcher.match(ticker("60000000"));

        // then
        verify(matchedOrderPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("Redis CircuitBreaker가 OPEN 상태면 DB에서 매칭 주문을 조회한다")
    void givenCircuitBreakerOpen_whenMatch_thenUseDbFallback() {
        // given
        redisCircuitBreaker.transitionToOpenState();
        given(pendingOrderDbRepository.findMatchedOrderIds("UPBIT", "BTC", "BUY", new BigDecimal("50000000")))
                .willReturn(List.of(500L, 501L));
        given(pendingOrderDbRepository.findMatchedOrderIds("UPBIT", "BTC", "SELL", new BigDecimal("50000000")))
                .willReturn(List.of());
        given(matchedOrderPublisher.publish(any(MatchedOrderMessage.class))).willReturn(true);

        // when
        matcher.match(ticker("50000000"));

        // then
        ArgumentCaptor<MatchedOrderMessage> captor = ArgumentCaptor.forClass(MatchedOrderMessage.class);
        verify(matchedOrderPublisher).publish(captor.capture());
        List<Long> matchedIds = captor.getValue().matched().stream()
                .map(MatchedOrderMessage.Item::orderId)
                .toList();
        assertThat(matchedIds).containsExactlyInAnyOrder(500L, 501L);
    }

    @Test
    @DisplayName("DB 폴백으로 매칭된 주문은 Redis ZREM을 수행하지 않는다")
    void givenDbFallback_whenMatch_thenDoNotRemoveFromRedis() {
        // given
        redisRepository.add("UPBIT", "BTC/KRW", "BUY", "100", 50_000_000d);
        redisCircuitBreaker.transitionToOpenState();
        given(pendingOrderDbRepository.findMatchedOrderIds(any(), any(), any(), any()))
                .willReturn(List.of(999L));
        given(matchedOrderPublisher.publish(any(MatchedOrderMessage.class))).willReturn(true);

        // when
        matcher.match(ticker("50000000"));

        // then — Redis ZSet의 기존 주문은 건드리지 않아야 한다 (DB 폴백 시 ZREM 스킵)
        Set<String> remaining = redisRepository.findMatchedBuyOrders(
                "UPBIT", "BTC/KRW", new BigDecimal("0"));
        assertThat(remaining).containsExactly("100");
    }
}
