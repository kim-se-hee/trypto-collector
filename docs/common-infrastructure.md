# 공통 인프라

각 거래소에서는 각자의 RestClient + WebSocketHandler만 구현하면 되도록, 모든 공유 코드를 구현한다.

---

## 구현 항목 목록

| 패키지 | 클래스 | 유형 |
|--------|--------|------|
| `common.config` | `RedisConfig` | `@Configuration` |
| `common.config` | `WebClientConfig` | `@Configuration` |
| `common.model` | `Exchange` | enum |
| `common.model` | `NormalizedTicker` | record |
| `metadata.model` | `MarketInfo` | record |
| `metadata` | `MarketInfoCache` | `@Component` |
| `metadata` | `ExchangeInitializer` | `@Component` |
| `client.websocket` | `ExchangeTickerStream` | interface |
| `redis` | `TickerRedisRepository` | `@Component` |
| `collector` | `RealtimePriceCollector` | `@Component` |
| — | `build.gradle` | 빌드 설정 |
| — | `application.yml` | 설정 파일 |

> `UpbitMarketResponse`와 `BithumbMarketResponse`는 업비트/빗썸 REST 응답 구조가 동일하지만, 각 거래소 REST 클라이언트가 자신의 DTO를 사용하도록 별도 record로 분리한다.

---

## 상세 명세

### 의존성

| 분류 | 의존성 |
|------|--------|
| Redis | `spring-boot-starter-data-redis-reactive` |
| WebFlux | `spring-boot-starter-webflux` |
| Lombok | `lombok` (compileOnly + annotationProcessor) |
| 테스트 | `spring-boot-starter-test`, `reactor-test` |

---

### application.yml

```yaml
spring:
  application:
    name: trypto-collector
  data:
    redis:
      host: localhost
      port: 6379

exchange:
  upbit:
    rest-url: https://api.upbit.com/v1/market/all
    ws-url: wss://api.upbit.com/websocket/v1
  bithumb:
    rest-url: https://api.bithumb.com/v1/market/all?isDetails=false
    ws-url: wss://pubwss.bithumb.com/pub/ws
  binance:
    rest-url: https://api.binance.com/api/v3/ticker/24hr
    ws-url: wss://stream.binance.com:9443/ws/!ticker@arr

ticker:
  redis-ttl-seconds: 30
  redis-key-prefix: ticker

logging:
  level:
    ksh.tryptocollector: DEBUG
    ksh.tryptocollector.client.websocket: INFO
```

---

### Exchange (enum)

`UPBIT`, `BITHUMB`, `BINANCE` 세 값을 가지는 단순 enum이다.

---

### NormalizedTicker (record)

세 거래소의 시세를 통일된 구조로 표현한다. 패키지: `common.model`

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

**변동률 기준 차이:**
- 업비트/빗썸: 전일 종가 대비 (`signed_change_rate`)
- 바이낸스: 24시간 롤링 윈도우 대비 (`P` / 100)

---

### MarketInfo (record)

거래소별 마켓 메타데이터를 저장한다. 패키지: `metadata.model`

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

모든 거래소 WebSocket 핸들러가 구현하는 인터페이스. `Mono<Void> connect()` 메서드 하나만 정의한다. 패키지: `client.websocket`

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

### RedisConfig (@Configuration)

`ReactiveRedisTemplate<String, String>` 빈을 등록한다. `StringRedisSerializer`로 키와 값을 모두 직렬화한다. 패키지: `common.config`

---

### WebClientConfig (@Configuration)

`WebClient` 빈을 등록한다. 바이낸스 REST API(`/api/v3/ticker/24hr`)는 2000+ 심볼을 반환하므로 `maxInMemorySize`를 5MB로 설정한다 (기본 256KB로는 부족). 패키지: `common.config`

---

### RealtimePriceCollector (@Component)

거래소별 WebSocket 연결을 트리거하는 오케스트레이터. 패키지: `collector`

**의존성:** `UpbitWebSocketHandler`, `BithumbWebSocketHandler`, `BinanceWebSocketHandler`

| 메서드 | 동작 |
|--------|------|
| `connectUpbit()` | `upbitWebSocketHandler.connect().subscribe()` |
| `connectBithumb()` | `bithumbWebSocketHandler.connect().subscribe()` |
| `connectBinance()` | `binanceWebSocketHandler.connect().subscribe()` |

`ExchangeInitializer`가 메타데이터 로딩 완료 후 해당 메서드를 호출한다. 공통 브랜치에서는 뼈대만 구현하고, 거래소별 WebSocketHandler 의존성은 각 거래소 브랜치에서 주입한다.

---

## 거래소 브랜치에서 구현할 항목

공통 브랜치 완료 후, 각 거래소 브랜치에서는 다음만 구현한다:

| 브랜치 | 구현 클래스 |
|--------|------------|
| `feature/collector-upbit` | `UpbitRestClient`, `UpbitTickerMessage`, `UpbitWebSocketHandler` |
| `feature/collector-bithumb` | `BithumbRestClient`, `BithumbTickerMessage`, `BithumbWebSocketHandler` |
| `feature/collector-binance` | `BinanceRestClient`, `BinanceTickerResponse`, `BinanceTickerMessage`, `BinanceWebSocketHandler` |

거래소 브랜치 간 파일 충돌이 없도록 설계되어 있다.
