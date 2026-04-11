package ksh.tryptocollector.config;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.ApplicationEventPublisher;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("LeaderElection 분산 리더 선출 통합 테스트")
class LeaderElectionIntegrationTest {

    private static RedisContainer redisContainer;
    private static RedissonClient redissonA;
    private static RedissonClient redissonB;

    private final List<LeaderElection> activeElections = new ArrayList<>();

    @BeforeAll
    static void startContainer() {
        redisContainer = new RedisContainer(DockerImageName.parse("redis:7-alpine"));
        redisContainer.start();

        redissonA = createRedisson();
        redissonB = createRedisson();
    }

    @AfterAll
    static void stopContainer() {
        if (redissonA != null) redissonA.shutdown();
        if (redissonB != null) redissonB.shutdown();
        if (redisContainer != null) redisContainer.stop();
    }

    private static RedissonClient createRedisson() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisContainer.getRedisHost() + ":" + redisContainer.getRedisPort());
        return Redisson.create(config);
    }

    @BeforeEach
    void setup() {
        // 이전 테스트 흔적 제거
        redissonA.getKeys().delete("collector:leader");
        activeElections.clear();
    }

    @AfterEach
    void teardown() {
        for (LeaderElection election : activeElections) {
            try {
                election.shutdown();
            } catch (Exception ignored) {
                // 이미 shutdown된 인스턴스거나 비정상 상태 — 테스트 teardown에서는 무시
            }
        }
        activeElections.clear();
    }

    private LeaderElection startElection(RedissonClient client, RecordingEventPublisher publisher) {
        LeaderElection election = new LeaderElection(client, publisher);
        election.start();
        activeElections.add(election);
        return election;
    }

    private void shutdownEarly(LeaderElection election) {
        election.shutdown();
        activeElections.remove(election);
    }

    @Test
    @DisplayName("단일 인스턴스는 부팅 직후 리더가 되고 LeadershipAcquiredEvent를 발행한다")
    void singleInstanceAcquiresLeadershipAndPublishesEvent() {
        RecordingEventPublisher publisher = new RecordingEventPublisher();
        LeaderElection election = startElection(redissonA, publisher);

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> {
                    assertThat(election.isLeader()).isTrue();
                    assertThat(publisher.events()).hasAtLeastOneElementOfType(LeadershipAcquiredEvent.class);
                });
    }

    @Test
    @DisplayName("두 인스턴스가 동시에 경쟁하면 한 쪽만 리더가 된다 (배타성)")
    void onlyOneInstanceBecomesLeaderWhenTwoRaceForLock() {
        LeaderElection electionA = startElection(redissonA, new RecordingEventPublisher());
        LeaderElection electionB = startElection(redissonB, new RecordingEventPublisher());

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(electionA.isLeader() || electionB.isLeader()).isTrue());

        // 최소 6초 동안 두 인스턴스가 동시에 리더가 되면 안 된다 (tick 한 사이클 이상)
        await().during(Duration.ofSeconds(6))
                .atMost(Duration.ofSeconds(7))
                .until(() -> !(electionA.isLeader() && electionB.isLeader()));
    }

    @Test
    @DisplayName("리더가 shutdown하면 standby 인스턴스가 다음 tick에 리더로 승격된다")
    void standbyPromotesAfterLeaderShutdown() {
        LeaderElection electionA = startElection(redissonA, new RecordingEventPublisher());
        LeaderElection electionB = startElection(redissonB, new RecordingEventPublisher());

        // 한 쪽이 리더가 될 때까지 대기
        await().atMost(Duration.ofSeconds(5))
                .until(() -> electionA.isLeader() || electionB.isLeader());

        // 리더/스탠바이 식별
        LeaderElection leader = electionA.isLeader() ? electionA : electionB;
        LeaderElection standby = (leader == electionA) ? electionB : electionA;
        assertThat(standby.isLeader()).isFalse();

        // 리더를 gracefully shutdown → 락 자발적 반환
        shutdownEarly(leader);

        // standby가 tick cycle(최대 5초) 내 리더로 승격되어야 한다
        await().atMost(Duration.ofSeconds(8))
                .until(standby::isLeader);
    }

    /**
     * 이벤트 발행을 캡처하는 간단한 테스트 더블
     */
    private static class RecordingEventPublisher implements ApplicationEventPublisher {
        private final List<Object> events = new CopyOnWriteArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }

        List<Object> events() {
            return events;
        }
    }
}
