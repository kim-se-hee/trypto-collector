package ksh.tryptocollector.config;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class LeaderElection {

    private static final String LEADER_LOCK_KEY = "collector:leader";
    private static final long ACQUIRE_INTERVAL_SECONDS = 5;
    private static final long UNLOCK_TIMEOUT_SECONDS = 3;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final String THREAD_NAME = "leader-election";

    private final RedissonClient redissonClient;
    private final ApplicationEventPublisher eventPublisher;

    private ScheduledExecutorService scheduler;
    private RLock lock;
    private volatile boolean leader = false;

    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        start();
    }

    void start() {
        lock = redissonClient.getLock(LEADER_LOCK_KEY);
        scheduler = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, THREAD_NAME));
        scheduler.scheduleWithFixedDelay(
                this::tick, 0, ACQUIRE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        if (leader) {
            releaseLeadershipGracefully();
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean isLeader() {
        return leader;
    }

    private void tick() {
        try {
            if (leader) {
                if (!lock.isHeldByCurrentThread()) {
                    leader = false;
                    log.warn("리더십 상실");
                    eventPublisher.publishEvent(new LeadershipRevokedEvent(this));
                }
                return;
            }
            if (lock.tryLock(0, -1, TimeUnit.SECONDS)) {
                leader = true;
                log.info("리더십 획득");
                eventPublisher.publishEvent(new LeadershipAcquiredEvent(this));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("리더 선출 tick 실패: {}", e.getMessage());
        }
    }

    private void releaseLeadershipGracefully() {
        Future<?> task = scheduler.submit(() -> {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                    log.info("리더십 자발적 반환");
                }
            } catch (Exception e) {
                log.warn("리더십 반환 실패: {}", e.getMessage());
            }
        });
        try {
            task.get(UNLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("리더십 반환 타임아웃: {}", e.getMessage());
        }
    }
}
