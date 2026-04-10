package ksh.tryptocollector.matching;

import io.lettuce.core.event.Event;
import io.lettuce.core.event.connection.ConnectionActivatedEvent;
import io.lettuce.core.event.connection.DisconnectedEvent;
import io.lettuce.core.resource.ClientResources;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import ksh.tryptocollector.config.LeaderElection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRecoveryWarmupListener {

    private final LettuceConnectionFactory connectionFactory;
    private final PendingOrderWarmupService warmupService;
    private final LeaderElection leaderElection;

    private Disposable subscription;
    private volatile boolean wasDisconnected = false;

    @PostConstruct
    void subscribe() {
        ClientResources resources = connectionFactory.getClientResources();
        subscription = resources.eventBus().get().subscribe(this::onEvent);
        log.info("Redis 연결 이벤트 구독 시작");
    }

    @PreDestroy
    void unsubscribe() {
        if (subscription != null) {
            subscription.dispose();
        }
    }

    private void onEvent(Event event) {
        if (event instanceof DisconnectedEvent) {
            wasDisconnected = true;
            log.warn("Redis 연결 끊김 감지");
            return;
        }
        if (event instanceof ConnectionActivatedEvent && wasDisconnected) {
            wasDisconnected = false;
            if (!leaderElection.isLeader()) {
                log.info("Redis 복구 감지했으나 리더 아님, 웜업 스킵");
                return;
            }
            log.info("Redis 복구 감지, 웜업 실행");
            try {
                warmupService.warmup();
            } catch (Exception e) {
                log.error("Redis 복구 웜업 실패", e);
            }
        }
    }
}
