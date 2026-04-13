package ksh.tryptocollector.config;

import ksh.tryptocollector.matching.PendingOrderWarmupService;
import ksh.tryptocollector.metadata.ExchangeInitializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LeaderLifecycleListener {

    private final PendingOrderWarmupService warmupService;
    private final ExchangeInitializer exchangeInitializer;

    @EventListener
    public void onAcquired(LeadershipAcquiredEvent event) {
        log.info("리더 활성화 시퀀스 시작");
        try {
            warmupService.warmup();
        } catch (Exception e) {
            log.warn("웜업 실패, 시세 수집은 계속 진행: {}", e.getMessage());
        }
        exchangeInitializer.start();
        log.info("리더 활성화 시퀀스 완료");
    }

    @EventListener
    public void onRevoked(LeadershipRevokedEvent event) {
        log.info("리더 비활성화 시퀀스 시작");
        exchangeInitializer.stop();
        log.info("리더 비활성화 시퀀스 완료");
    }
}
