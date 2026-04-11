package ksh.tryptocollector.matching;

import ksh.tryptocollector.config.LeaderElection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompensationScheduler 단위 테스트")
class CompensationSchedulerTest {

    @Mock
    private PendingOrderDbRepository pendingOrderDbRepository;

    @Mock
    private PendingOrderRedisRepository pendingOrderRedisRepository;

    @Mock
    private MatchedOrderPublisher matchedOrderPublisher;

    @Mock
    private TickRawRepository tickRawRepository;

    @Mock
    private LeaderElection leaderElection;

    @InjectMocks
    private CompensationScheduler scheduler;

    @BeforeEach
    void defaultLeader() {
        // 대부분의 테스트는 리더 상태에서 실행
    }

    @Test
    @DisplayName("리더가 아니면 compensate는 DB/Redis/Influx에 아무 동작도 하지 않는다")
    void nonLeaderSkipsCompensation() {
        given(leaderElection.isLeader()).willReturn(false);

        scheduler.compensate();

        verifyNoInteractions(pendingOrderDbRepository, pendingOrderRedisRepository,
                matchedOrderPublisher, tickRawRepository);
    }

    @Test
    @DisplayName("리더인데 PENDING 주문이 없으면 InfluxDB/Redis 접근 없이 리턴한다")
    void leaderWithEmptyPendingListDoesNothing() {
        given(leaderElection.isLeader()).willReturn(true);
        given(pendingOrderDbRepository.findAllPendingOrders()).willReturn(List.of());

        scheduler.compensate();

        verifyNoInteractions(tickRawRepository, matchedOrderPublisher, pendingOrderRedisRepository);
    }

    @Test
    @DisplayName("InfluxDB에서 매칭 틱을 찾으면 MatchedOrderMessage를 publish한다")
    void publishesMatchWhenInfluxReturnsMatchingTick() {
        given(leaderElection.isLeader()).willReturn(true);

        PendingOrder order = new PendingOrder(
                100L, "UPBIT", "BTC", "BUY",
                new BigDecimal("50000000"), Instant.parse("2026-04-10T00:00:00Z"));
        given(pendingOrderDbRepository.findAllPendingOrders()).willReturn(List.of(order));
        given(tickRawRepository.findFirstMatchingPrice(
                eq("UPBIT"), eq("BTC/KRW"), eq("BUY"), eq(new BigDecimal("50000000")), any()))
                .willReturn(Optional.of(new BigDecimal("49500000")));

        scheduler.compensate();

        ArgumentCaptor<MatchedOrderMessage> captor = ArgumentCaptor.forClass(MatchedOrderMessage.class);
        verify(matchedOrderPublisher).publish(captor.capture());

        MatchedOrderMessage.Item item = captor.getValue().matched().getFirst();
        assertThat(item.orderId()).isEqualTo(100L);
        assertThat(item.filledPrice()).isEqualByComparingTo("49500000");

        // 매칭되면 Redis ZSet에 추가하지 않음
        verify(pendingOrderRedisRepository, never()).add(any(), any(), any(), any(), anyDouble());
    }

    @Test
    @DisplayName("InfluxDB에 매칭 틱이 없으면 Redis ZSet에 주문을 추가한다")
    void addsToRedisZSetWhenNoInfluxMatch() {
        given(leaderElection.isLeader()).willReturn(true);

        PendingOrder order = new PendingOrder(
                200L, "BINANCE", "ETH", "SELL",
                new BigDecimal("3500"), Instant.parse("2026-04-10T00:00:00Z"));
        given(pendingOrderDbRepository.findAllPendingOrders()).willReturn(List.of(order));
        given(tickRawRepository.findFirstMatchingPrice(
                eq("BINANCE"), eq("ETH/USDT"), eq("SELL"), eq(new BigDecimal("3500")), any()))
                .willReturn(Optional.empty());

        scheduler.compensate();

        verify(pendingOrderRedisRepository).add(
                "BINANCE", "ETH/USDT", "SELL", "200", 3500d);
        verify(matchedOrderPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("한 주문 처리 중 예외가 발생해도 다른 주문의 처리를 막지 않는다")
    void failuresOnOneOrderDoNotStopOthers() {
        given(leaderElection.isLeader()).willReturn(true);

        PendingOrder failing = new PendingOrder(
                1L, "UPBIT", "BTC", "BUY",
                new BigDecimal("50000000"), Instant.parse("2026-04-10T00:00:00Z"));
        PendingOrder succeeding = new PendingOrder(
                2L, "UPBIT", "ETH", "BUY",
                new BigDecimal("3000000"), Instant.parse("2026-04-10T00:00:00Z"));
        given(pendingOrderDbRepository.findAllPendingOrders()).willReturn(List.of(failing, succeeding));

        given(tickRawRepository.findFirstMatchingPrice(
                eq("UPBIT"), eq("BTC/KRW"), any(), any(), any()))
                .willThrow(new RuntimeException("influx down"));
        given(tickRawRepository.findFirstMatchingPrice(
                eq("UPBIT"), eq("ETH/KRW"), any(), any(), any()))
                .willReturn(Optional.empty());

        scheduler.compensate();

        // 두 번째 주문은 Redis ZSet에 정상 추가되어야 한다
        verify(pendingOrderRedisRepository).add(
                "UPBIT", "ETH/KRW", "BUY", "2", 3000000d);
    }

    private static double anyDouble() {
        return org.mockito.ArgumentMatchers.anyDouble();
    }
}
