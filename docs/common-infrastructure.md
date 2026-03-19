# 공통 인프라

각 거래소에서는 각자의 RestClient + WebSocketHandler만 구현하면 되도록, 모든 공유 코드를 구현한다.

---

## 상세 명세

### 의존성

| 분류 | 의존성 |
|------|--------|
| Redis | `spring-boot-starter-data-redis` |
| RabbitMQ | `spring-boot-starter-amqp` |
| Web | `spring-boot-starter-web` |
| InfluxDB | `influxdb-client-java` |
| Lombok | `lombok` (compileOnly + annotationProcessor) |
| 테스트 | `spring-boot-starter-test` |

---

### Exchange (enum)

`UPBIT`, `BITHUMB`, `BINANCE` 세 값을 가지는 단순 enum이다.

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

RabbitMQ로 발행하는 시세 변경 이벤트 메시지. 트레이딩 서버가 미체결 주문 매칭에 사용한다. 패키지: `model`

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

`@PostConstruct`에서 `ExecutorService(3)`로 각 거래소의 초기화를 별도 스레드에서 실행한다. 패키지: `metadata`

**의존성:** 각 거래소 RestClient, 각 거래소 WebSocketHandler, MarketInfoCache, TickerRedisRepository, MarketMetadataRedisRepository

**초기화 흐름:** `init()`에서 `loadAndConnectUpbit()`, `loadAndConnectBithumb()`, `loadAndConnectBinance()`를 각각 별도 스레드에서 실행한다.

| 메서드 | 흐름 |
|--------|------|
| `loadAndConnectUpbit()` | REST 호출 → 캐시 적재 → Redis 메타데이터 저장 → `upbitWebSocketHandler.connect()` |
| `loadAndConnectBithumb()` | REST 호출 → 캐시 적재 → Redis 메타데이터 저장 → `bithumbWebSocketHandler.connect()` |
| `loadAndConnectBinance()` | REST 호출 → 캐시 적재 + Redis 초기 스냅샷 → Redis 메타데이터 저장 → `binanceWebSocketHandler.connect()` |

각 초기화는 독립적으로 실행된다. 하나가 실패해도 나머지에 영향이 없다. `initWithRetry()`로 감싸서 실패 시 무한 재시도(지수 백오프, 최대 60초)로 복구한다.

**바이낸스 초기 스냅샷:** REST API 응답의 `lastPrice`와 `priceChangePercent`를 즉시 Redis에 저장하여 WebSocket 연결 전에도 시세를 제공한다.

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

거래소별 마켓 메타데이터를 Redis에 JSON 배열로 저장한다. 백엔드(trypto-backend)가 기동 시 조회하여 coin, exchange_coin 테이블에 저장한다. 패키지: `redis`

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

RabbitMQ 설정. Fanout Exchange 선언과 Publisher Confirms가 설정된 `RabbitTemplate` 빈을 등록한다. 패키지: `rabbitmq`

| 항목 | 값 |
|------|-----|
| Exchange 이름 | `ticker.exchange` |
| Exchange 타입 | Fanout |
| Publisher Confirms | `correlated` 모드 (nack 시 로그 경고) |

큐 바인딩은 소비자(트레이딩 서버)가 담당한다. 수집기는 Exchange만 선언한다.

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

### TickerSinkProcessor (@Component)

WebSocket 핸들러가 정규화한 시세를 받아 모든 싱크(Redis, RabbitMQ, CandleBuffer)에 전달하는 프로세서. 패키지: `exchange`

**의존성:** `TickerRedisRepository`, `TickerEventPublisher`, `CandleBuffer`

| 항목 | 설명 |
|------|------|
| `process(NormalizedTicker)` | CandleBuffer 갱신 → Redis 저장 → RabbitMQ 발행 |
| 에러 격리 | CandleBuffer 갱신 실패 시 예외를 catch하여 Redis/RabbitMQ 처리를 계속한다 |

