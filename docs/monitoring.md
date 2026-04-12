# 개요

- 파이프라인이 ~2,400개 코인의 실시간 시세를 지연 없이 처리하는지 확인하려면 내부 계측이 필요하다.
- 차트 빈 구간, 주문 매칭 누락 같은 장애가 발생해도 측정 없이는 원인을 특정할 수 없다.

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

## 직접 계측 (6개)

| 메트릭 | 타입 | 태그 | 컴포넌트 | 역할 |
|--------|------|------|----------|------|
| `ticker.latency` | Timer | `exchange` | `TickerSinkProcessor` | 파이프라인 처리 시간 (메서드 진입 → Redis/RabbitMQ/매칭 완료) |
| `rabbitmq.publish` | Counter | `exchange` | `TickerEventPublisher` | RabbitMQ 발행 성공 횟수 (거래소별 처리량 측정) |
| `ticker.parse.failure` | Counter | `exchange` | `{거래소}WebSocketHandler` | WebSocket 메시지 파싱 실패 횟수 |
| `websocket.reconnect` | Counter | `exchange` | `{거래소}WebSocketHandler` | WebSocket 재연결 횟수 (while 루프 내부 분기) |
| `rabbitmq.nack.count` | Counter | — | `RabbitMQConfig` | 브로커 메시지 수신 거부 횟수 (confirm 콜백) |
| `matching.matched.count` | Counter | `exchange` | `PendingOrderMatcher` | 주문 매칭 건수 (거래소별) |

## 자동 수집

| 메트릭 | 역할 |
|--------|------|
| JVM Heap / GC / Threads | 메모리 누수, GC 정지 감지 |
| `executor.pool.size` / `executor.active` | `ExecutorService` 스레드풀 사용량 (`ExecutorServiceMetrics` 등록) |

---

# 설계 결정

## `ticker.latency`를 AOP 대신 직접 계측하는 이유

`@Timed` AOP는 어노테이션에 정적 태그만 지정할 수 있다. `exchange` 태그는 메서드 파라미터(`NormalizedTicker.exchange()`)에서 동적으로 추출해야 하므로 `MeterRegistry`로 직접 등록한다.

