# 개요

- 파이프라인이 ~2,400개 코인의 실시간 시세를 지연 없이 처리하는지 확인하려면 내부 계측이 필요하다.
- 차트 빈 구간, engine 발행 누락 같은 장애가 발생해도 측정 없이는 원인을 특정할 수 없다.

# 측정 전략

Micrometer + Spring Boot Actuator로 측정한다.

- **AOP 계측**: `@Timed`, `@Counted` 어노테이션으로 메서드 단위 메트릭을 자동 수집한다. 비즈니스 코드에 계측 로직을 삽입하지 않는다. `TimedAspect`, `CountedAspect` 빈 등록이 필요하다.
- **직접 계측**: AOP로 잡을 수 없는 메트릭(동적 태그, while 루프 내부 분기, 콜백)은 `MeterRegistry`를 주입하여 직접 등록한다.

---

# 메트릭 목록

## AOP 계측 (어노테이션 기반, 1개)

| 메트릭 | 타입 | 태그 | 어노테이션 | 대상 메서드 | 역할 |
|--------|------|------|-----------|------------|------|
| `redis.write.time` | Timer | — | `@Timed` | `TickerRedisRepository.save()` | Redis SET 소요 시간 |

## 직접 계측 — 시세 파이프라인 (5개)

| 메트릭 | 타입 | 태그 | 컴포넌트 | 역할 |
|--------|------|------|----------|------|
| `rabbitmq.publish` | Counter | `exchange` | `TickerEventPublisher` | `ticker.exchange` Fanout 발행 성공 횟수 (거래소별 처리량 측정) |
| `engine.inbox.tick.publish` | Counter | `exchange` | `EngineInboxPublisher` | `engine.inbox` 큐 tick 발행 성공 횟수 (매칭 엔진으로의 유입량 측정) |
| `ticker.parse.failure` | Counter | `exchange` | `{거래소}WebSocketHandler` | WebSocket 메시지 파싱 실패 횟수 |
| `websocket.reconnect` | Counter | `exchange` | `{거래소}WebSocketHandler` | WebSocket 재연결 횟수 (while 루프 내부 분기) |
| `rabbitmq.nack.count` | Counter | — | `RabbitMQConfig` | 브로커 메시지 수신 거부 횟수 (confirm 콜백) |

## 자동 수집

| 메트릭 | 역할 |
|--------|------|
| JVM Heap / GC / Threads | 메모리 누수, GC 정지 감지 |
| `executor.pool.size` / `executor.active` | `ExecutorService` 스레드풀 사용량 (`ExecutorServiceMetrics` 등록) |

---

# 매칭 엔진 관측

주문 매칭·체결·WAL·DB 쓰기 관련 메트릭은 collector가 아니라 trypto-engine이 노출한다. collector 리포의 `grafana/dashboards/engine-overview.json` 대시보드가 다음 메트릭들을 시각화한다.

| 메트릭 출처 | 내용 |
|-------------|------|
| `engine_inbox_*` | 큐 depth, consumer rate |
| `engine_match_*` | 매칭 처리량, per-tick 매칭 수 |
| `engine_wal_batch_*` | WAL 배치 처리 지연 (writes + fsync / 배치) |
| `engine_db_write_*` | 체결 결과 DB 배치 write rate/latency |
| `engine_order_fill_*` | 단건 주문 체결 시간 |

collector 측 SLO는 "발행 성공률"(`engine.inbox.tick.publish` 누적값과 tick 수신량의 비율)까지이며, 그 이후 매칭 E2E 지연은 engine 리포의 대시보드로 추적한다.

---

# 설계 결정

## collector에서 E2E 매칭 레이턴시를 측정하지 않는 이유

매칭 로직이 trypto-engine으로 분리되면서 collector는 "발행까지"만 책임진다. "매칭 시작 → DB 체결 완료" 구간은 engine 내부에서 WAL batch, DB batch 단위로 측정하는 쪽이 인과관계가 명확하다. collector가 발행한 tick이 engine에 도달했는지는 `engine.inbox` 큐 depth로 역추적할 수 있다.

## `rabbitmq.publish`와 `engine.inbox.tick.publish`를 분리하는 이유

같은 WebSocket tick이 두 경로로 팬아웃되지만 소비자와 토폴로지가 다르다(Fanout Exchange vs. durable queue). 한쪽이 실패해도 다른 쪽은 정상일 수 있으므로 카운터를 분리하여 장애 구간을 식별한다.
