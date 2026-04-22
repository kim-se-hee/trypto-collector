# 공통 인프라

각 거래소에서는 각자의 RestClient + WebSocketHandler만 구현하면 되도록, 모든 공유 코드를 구현한다.

---

## 상세 명세

### 의존성

| 분류 | 의존성 |
|------|--------|
| Web | `spring-boot-starter-web`, `spring-boot-starter-restclient` |
| Redis | `spring-boot-starter-data-redis` |
| RabbitMQ | `spring-boot-starter-amqp` |
| DB | `spring-boot-starter-jdbc`, `mysql-connector-j` (runtime) |
| InfluxDB | `influxdb-client-java` |
| 분산 락 | `redisson` (코어, spring-data-redis 충돌 방지) |
| 서킷 브레이커 | `resilience4j-circuitbreaker`, `resilience4j-micrometer` |
| 모니터링 | `spring-boot-starter-actuator`, `micrometer-registry-prometheus` (runtime) |
| Lombok | `lombok` (compileOnly + annotationProcessor) |
| 테스트 | `spring-boot-starter-test`, Testcontainers (Redis, MySQL, RabbitMQ), `awaitility` |

---

### Exchange (enum)

`UPBIT`, `BITHUMB`, `BINANCE` 세 값을 가진다. 각 거래소의 결제 통화를 `quote` 필드로 보유한다 (`UPBIT`/`BITHUMB` → `"KRW"`, `BINANCE` → `"USDT"`).

---

### NormalizedTicker (record)

세 거래소의 시세를 통일된 구조로 표현한다. 패키지: `model`

| 필드 | 타입 | 설명 | 예시 |
|------|------|------|------|
| `exchange` | `String` | 거래소 이름 | `"UPBIT"`, `"BINANCE"` |
| `base` | `String` | 기준 통화 | `"BTC"`, `"ETH"` |
| `quote` | `String` | 결제 통화 | `"KRW"`, `"USDT"` |
| `displayName` | `String` | 표시명 | 한국 거래소: `"비트코인"`, 바이낸스: `"BTC"` |
| `lastPrice` | `BigDecimal` | 최종 체결가 | |
| `changeRate` | `BigDecimal` | 변동률 (비율) | `0.0123` = +1.23% |
| `quoteTurnover` | `BigDecimal` | 24시간 거래대금 (quote 통화 기준) | |
| `tsMs` | `long` | 수집기 수신 시각 (epoch millis) | |

변동률 기준 차이는 `architecture.md`의 설계 결정 섹션을 참조한다.

---

### TickerEvent (record)

RabbitMQ로 발행하는 시세 변경 이벤트 메시지. 트레이딩 서버가 WebSocket 브로드캐스트에 사용한다. 패키지: `model`

| 필드 | 타입 | 설명 |
|------|------|------|
| `exchange` | `String` | 거래소 명 (UPBIT, BITHUMB, BINANCE) |
| `symbol` | `String` | 거래 페어 (BTC/KRW, ETH/USDT) |
| `currentPrice` | `BigDecimal` | 변경된 현재가 |
| `changeRate` | `BigDecimal` | 전일 대비 변동률 |
| `timestamp` | `long` | 시세 수신 시각 (epoch ms) |

`NormalizedTicker`에서 변환하는 `from(NormalizedTicker)` 팩토리 메서드를 제공한다. `symbol`은 `base + "/" + quote` 형식으로 조합한다.

---

### MarketInfo (record)

거래소별 마켓 메타데이터를 저장한다. 패키지: `model`

| 필드 | 타입 | 설명 | 예시 |
|------|------|------|------|
| `base` | `String` | 기준 통화 | `"BTC"` |
| `quote` | `String` | 결제 통화 | `"KRW"`, `"USDT"` |
| `pair` | `String` | 페어 문자열 | `"BTC/KRW"`, `"BTC/USDT"` |
| `displayName` | `String` | 표시명 | 한국 거래소: `"비트코인"`, 바이낸스: `"BTC"` |

---

### MarketInfoCache (@Component)

`ConcurrentHashMap` 기반 인메모리 캐시. 거래소 + 심볼 코드를 키로 `MarketInfo`를 저장한다. 패키지: `metadata`

**키 포맷:** `"{EXCHANGE}:{symbolCode}"` (예: `"UPBIT:KRW-BTC"`, `"BINANCE:BTCUSDT"`)

| 메서드 | 설명 |
|--------|------|
| `put(Exchange, String symbolCode, MarketInfo)` | 메타데이터 적재 |
| `find(Exchange, String symbolCode) → Optional<MarketInfo>` | WebSocket 핸들러에서 displayName 조회, 바이낸스 USDT 필터링에 사용 |
| `getSymbolCodes(Exchange) → List<String>` | 업비트/빗썸 WebSocket 구독 시 마켓 코드 목록 제공 |
| `getMarketInfos(Exchange) → List<MarketInfo>` | 특정 거래소의 모든 `MarketInfo` 반환. Redis 메타데이터 저장 시 사용 |
| `clear(Exchange)` | 특정 거래소 메타데이터 초기화 (재로딩 시 사용) |

---

### ExchangeInitializer (@Component)

리더 획득 시 `start()`로 `ExecutorService(3)`를 생성하여 각 거래소의 초기화를 별도 스레드에서 실행한다. 리더십 상실 시 `stop()`으로 스레드풀을 `shutdownNow()`한다. `@PreDestroy`에서도 `stop()`을 호출하여 non-daemon 스레드의 JVM hang을 방지한다. 패키지: `metadata`

**의존성:** 각 거래소 RestClient, 각 거래소 WebSocketHandler, MarketInfoCache, TickerRedisRepository, MarketMetadataRedisRepository, MeterRegistry

**라이프사이클:** `LeaderLifecycleListener`가 `LeadershipAcquiredEvent`에서 `start()`, `LeadershipRevokedEvent`에서 `stop()`을 호출한다. `start()`/`stop()` 모두 멱등(null 체크로 중복 호출 안전)하다.

| 메서드 | 흐름 |
|--------|------|
| `loadAndConnectUpbit()` | REST 마켓 조회 → 캐시 적재 → Redis 메타데이터 저장 → REST 시세 조회 → Redis 초기 스냅샷 저장 → `upbitWebSocketHandler.connect()` |
| `loadAndConnectBithumb()` | REST 마켓 조회 → 캐시 적재 → Redis 메타데이터 저장 → REST 시세 조회 → Redis 초기 스냅샷 저장 → `bithumbWebSocketHandler.connect()` |
| `loadAndConnectBinance()` | REST 시세 조회 → 캐시 적재 + Redis 초기 스냅샷 → Redis 메타데이터 저장 → `binanceWebSocketHandler.connect()` |

각 초기화는 독립적으로 실행된다. 하나가 실패해도 나머지에 영향이 없다. `initWithRetry()`로 감싸서 실패 시 무한 재시도(지수 백오프, 최대 60초)로 복구한다.

**초기 시세 스냅샷:** 세 거래소 모두 REST API로 현재 시세를 조회하여 Redis에 저장한다. WebSocket 연결 전에도 시세를 제공하기 위함이다.

---

### ExchangeTickerStream (interface)

모든 거래소 WebSocket 핸들러가 구현하는 인터페이스. `void connect()` 메서드 하나만 정의한다. 패키지: `exchange`

---

### TickerRedisRepository (@Component)

정규화된 시세를 Redis에 JSON으로 저장한다. 패키지: `redis`

**의존성:** `StringRedisTemplate`, `ObjectMapper`

**설정값:** `@Value`로 `ticker.redis-ttl-seconds`(기본 30)와 `ticker.redis-key-prefix`(기본 `"ticker"`)를 주입한다. `@Value` 파라미터가 있으므로 명시적 생성자를 작성한다.

| 항목 | 값 |
|------|-----|
| 키 포맷 | `{prefix}:{EXCHANGE}:{BASE}/{QUOTE}` (예: `ticker:UPBIT:BTC/KRW`) |
| 값 | JSON 직렬화된 NormalizedTicker |
| TTL | 30초 (쓰기마다 리셋) |

`save(NormalizedTicker)` 메서드 하나만 제공한다. `void`를 반환한다.

---

### MarketMetadataRedisRepository (@Component)

거래소별 마켓 메타데이터를 Redis에 JSON 배열로 저장한다. 백엔드(trypto-api)가 기동 시 조회하여 coin, exchange_coin 테이블에 저장한다. 패키지: `redis`

**의존성:** `StringRedisTemplate`, `ObjectMapper`

**설정값:** `@Value`로 `market-meta.redis-key-prefix`(기본 `"market-meta"`)를 주입한다.

| 항목 | 값 |
|------|-----|
| 키 포맷 | `{prefix}:{EXCHANGE}` (예: `market-meta:UPBIT`) |
| 값 | JSON 배열 `[{"base":"BTC","quote":"KRW","pair":"BTC/KRW","displayName":"비트코인"}, ...]` |
| TTL | 없음 (수집기 재기동 시 덮어쓰기) |

`save(Exchange, List<MarketInfo>)` 메서드 하나만 제공한다. `void`를 반환한다.

---

### RabbitMQConfig (@Configuration)

RabbitMQ 설정. Exchange 선언과 Publisher Confirms가 설정된 `RabbitTemplate` 빈을 등록한다. 패키지: `rabbitmq`

**시세 이벤트 (trypto-api 소비):**

| 항목 | 값 |
|------|-----|
| Exchange 이름 | `ticker.exchange` |
| Exchange 타입 | Fanout |
| Publisher Confirms | `correlated` 모드 (nack 시 로그 경고) |

큐 바인딩은 소비자(트레이딩 서버)가 담당한다. 수집기는 Exchange만 선언한다.

**engine.inbox 큐 (trypto-engine 소비):**

| 항목 | 값 |
|------|-----|
| Queue 이름 | `engine.inbox` |
| durable | `true` |
| 라우팅 | default exchange + routing key = 큐 이름 |
| 헤더 | `event_type=TickReceived` (collector가 발행하는 유일한 타입) |

큐 선언은 collector가 담당한다. trypto-engine은 이 큐를 단일 소비자로 구독하여 `OrderPlaced`/`OrderCanceled`/`TickReceived` 이벤트를 함께 처리한다.

---

### TickerEventPublisher (@Component)

`NormalizedTicker`를 `TickerEvent`로 변환하여 RabbitMQ Fanout Exchange에 발행한다. 패키지: `rabbitmq`

**의존성:** `RabbitTemplate`, `ObjectMapper`

| 항목 | 값 |
|------|-----|
| Exchange | `ticker.exchange` (Fanout) |
| Content-Type | `application/json` |
| 에러 처리 | 직렬화/발행 실패 시 로그 경고 (시세 수집을 중단하지 않음) |

`publish(NormalizedTicker)` 메서드 하나만 제공한다. `void`를 반환한다.

---

### EngineInboxPublisher (@Component)

`NormalizedTicker`를 tick 페이로드로 직렬화하여 RabbitMQ `engine.inbox` 큐에 발행한다. trypto-engine이 이 큐를 소비하여 주문 매칭을 수행한다. 패키지: `rabbitmq`

**의존성:** `RabbitTemplate`, `ObjectMapper`, `MeterRegistry`

**페이로드:**

```json
{
  "exchange": "UPBIT",
  "displayName": "BTC",
  "tradePrice": 50000000,
  "tickAt": "2026-04-22T10:04:00"
}
```

| 항목 | 값 |
|------|-----|
| Exchange | default exchange (`""`) |
| Routing Key | `engine.inbox` (큐 이름) |
| Content-Type | `application/json` |
| Header | `event_type=TickReceived` |
| 에러 처리 | 직렬화/발행 실패 시 로그 경고 (시세 수집을 중단하지 않음) |

`publish(NormalizedTicker)` 메서드 하나만 제공한다. `void`를 반환한다.

---

### TickerSinkProcessor (@Component)

WebSocket 핸들러가 정규화한 시세를 받아 모든 싱크(InfluxDB, Redis, RabbitMQ 시세 이벤트, engine.inbox)에 팬아웃하는 프로세서. 패키지: `exchange`

**의존성:** `TickerRedisRepository`, `TickerEventPublisher`, `EngineInboxPublisher`, `TickRawWriter`, `CircuitBreaker`(Redis)

| 항목 | 설명 |
|------|------|
| `process(NormalizedTicker)` | InfluxDB tick 기록 → Redis 저장 → RabbitMQ 시세 이벤트 발행 → engine.inbox tick 발행 |
| 에러 격리 | 개별 싱크의 실패가 다른 싱크에 영향을 주지 않도록 try/catch 격리한다. Redis는 서킷 브레이커 OPEN 시 스킵한다 |

---

### TickRawWriter (@Component)

시세 tick을 InfluxDB에 기록한다. InfluxDB Task가 이 데이터를 원본으로 캔들(OHLC)을 집계한다. 패키지: `tick`

**의존성:** `WriteApiBlocking` (InfluxDB)

| 항목 | 값 |
|------|-----|
| Measurement | `ticker_raw` |
| Tags | `exchange`, `symbol` |
| Field | `price` (double) |

`write(NormalizedTicker)` 메서드 하나만 제공한다. `void`를 반환한다.

---

### LeaderElection (@Component)

Redisson 분산 락 기반 리더 선출. 거래소 초기화는 리더 노드에서만 실행된다. 패키지: `config`

| 항목 | 값 |
|------|-----|
| 선출 간격 | 5초 |
| 방식 | Redisson 분산 락 |

`isLeader() → boolean` — 현재 노드가 리더인지 반환한다.

---

### CircuitBreakerConfig (@Configuration)

Resilience4j 서킷 브레이커 설정. Redis 접근에 적용한다. 패키지: `config`

| 항목 | 값 |
|------|-----|
| 슬라이딩 윈도우 | 5회 |
| 실패 임계치 | 60% |
| OPEN 대기 시간 | 10초 |
| HALF_OPEN 허용 호출 | 2회 |

