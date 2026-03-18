# 공통 인프라

각 거래소에서는 각자의 RestClient + WebSocketHandler만 구현하면 되도록, 모든 공유 코드를 구현한다.

---

## 상세 명세

### 의존성

| 분류 | 의존성 |
|------|--------|
| Redis | `spring-boot-starter-data-redis-reactive` |
| RabbitMQ | `spring-boot-starter-amqp` |
| WebFlux | `spring-boot-starter-webflux` |
| InfluxDB | `influxdb-client-java` |
| Lombok | `lombok` (compileOnly + annotationProcessor) |
| 테스트 | `spring-boot-starter-test`, `reactor-test` |

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
| `clear(Exchange)` | 특정 거래소 메타데이터 초기화 (재로딩 시 사용) |

---

### ExchangeInitializer (@Component)

`@PostConstruct`에서 각 거래소의 메타데이터를 독립적으로 로딩한다. `subscribe()`로 비동기 실행하므로 시작을 블로킹하지 않는다. 패키지: `metadata`

**의존성:** 각 거래소 RestClient, MarketInfoCache, RealtimePriceCollector, TickerRedisRepository

**초기화 흐름:** `init()`에서 `loadUpbit()`, `loadBithumb()`, `loadBinance()`를 각각 `subscribe()`로 실행한다.

| 메서드 | 흐름 |
|--------|------|
| `loadUpbit()` | REST 호출 → 캐시 적재 → `connectUpbit()` |
| `loadBithumb()` | REST 호출 → 캐시 적재 → `connectBithumb()` |
| `loadBinance()` | REST 호출 → 캐시 적재 + Redis 초기 스냅샷 → `connectBinance()` |

각 `load` 메서드는 독립적으로 실행된다. 하나가 실패해도 나머지에 영향이 없다. 실패 시 무한 재시도(지수 백오프)로 복구한다.

**바이낸스 초기 스냅샷:** REST API 응답의 `lastPrice`와 `priceChangePercent`를 즉시 Redis에 저장하여 WebSocket 연결 전에도 시세를 제공한다.

---

### ExchangeTickerStream (interface)

모든 거래소 WebSocket 핸들러가 구현하는 인터페이스. `Mono<Void> connect()` 메서드 하나만 정의한다. 패키지: `exchange`

---

### TickerRedisRepository (@Component)

정규화된 시세를 Redis에 JSON으로 저장한다. 패키지: `redis`

**의존성:** `ReactiveRedisTemplate<String, String>`, `ObjectMapper`

**설정값:** `@Value`로 `ticker.redis-ttl-seconds`(기본 30)와 `ticker.redis-key-prefix`(기본 `"ticker"`)를 주입한다. `@Value` 파라미터가 있으므로 명시적 생성자를 작성한다.

| 항목 | 값 |
|------|-----|
| 키 포맷 | `{prefix}:{EXCHANGE}:{BASE}/{QUOTE}` (예: `ticker:UPBIT:BTC/KRW`) |
| 값 | JSON 직렬화된 NormalizedTicker |
| TTL | 30초 (쓰기마다 리셋) |

`save(NormalizedTicker)` 메서드 하나만 제공한다. `Mono<Boolean>`을 반환한다.

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
| 스케줄링 | `Schedulers.boundedElastic()` (블로킹 RabbitTemplate 래핑) |
| 에러 처리 | 직렬화/발행 실패 시 로그 경고 후 `onErrorComplete()` (시세 수집을 중단하지 않음) |

`publish(NormalizedTicker)` 메서드 하나만 제공한다. `Mono<Void>`를 반환한다.

---

### WebClientConfig (@Configuration)

`WebClient` 빈을 등록한다. 바이낸스 REST API(`/api/v3/ticker/24hr`)는 2000+ 심볼을 반환하므로 `maxInMemorySize`를 5MB로 설정한다 (기본 256KB로는 부족). 패키지: `config`

---

### RealtimePriceCollector (@Component)

거래소별 WebSocket 연결을 트리거하는 오케스트레이터. 패키지: `exchange`

**의존성:** `UpbitWebSocketHandler`, `BithumbWebSocketHandler`, `BinanceWebSocketHandler`

| 메서드 | 동작 |
|--------|------|
| `connectUpbit()` | `upbitWebSocketHandler.connect().subscribe()` |
| `connectBithumb()` | `bithumbWebSocketHandler.connect().subscribe()` |
| `connectBinance()` | `binanceWebSocketHandler.connect().subscribe()` |

`ExchangeInitializer`가 메타데이터 로딩 완료 후 해당 메서드를 호출한다.

