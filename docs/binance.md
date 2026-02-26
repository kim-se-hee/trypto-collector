# 바이낸스 (feature/collector-binance)

## REST API

### 24시간 티커 조회

- **URL:** `GET https://api.binance.com/api/v3/ticker/24hr`
- **인증:** 불필요 (공개 마켓 데이터)
- **응답 크기:** 2000+ 심볼을 포함하는 대형 JSON 배열 → WebClient `maxInMemorySize` 5MB 필요
- **필터:** `symbol`이 `USDT`로 끝나는 항목만 사용

**응답 예시 (단일 항목):**

```json
{
  "symbol": "BTCUSDT",
  "priceChange": "400.50000000",
  "priceChangePercent": "1.230",
  "weightedAvgPrice": "32100.50000000",
  "prevClosePrice": "31883.00000000",
  "lastPrice": "32287.00000000",
  "lastQty": "0.01200000",
  "bidPrice": "32286.50000000",
  "bidQty": "1.50000000",
  "askPrice": "32287.50000000",
  "askQty": "0.80000000",
  "openPrice": "31886.50000000",
  "highPrice": "32310.00000000",
  "lowPrice": "31855.00000000",
  "volume": "24500.12300000",
  "quoteVolume": "786543210.50000000",
  "openTime": 1676878862000,
  "closeTime": 1676965262000,
  "firstId": 1234567890,
  "lastId": 1234599999,
  "count": 32109
}
```

**DTO (사용 필드만):**

```java
// client/rest/dto/BinanceTickerResponse.java
public record BinanceTickerResponse(
    String symbol,               // "BTCUSDT"
    String lastPrice,            // "32287.00000000"
    String priceChangePercent,   // "1.230"
    String quoteVolume           // "786543210.50000000"
) {}
```

**MarketInfo 변환:**

```
symbol: "BTCUSDT"
→ base: "BTC"           (symbol.replace("USDT", ""))
→ quote: "USDT"
→ pair: "BTC/USDT"
→ displayName: "BTC"    (바이낸스는 한글명 없음, base 심볼 사용)
→ 캐시 키: "BINANCE:BTCUSDT"
```

> `replace("USDT", "")`는 안전하다. `1000SHIBUSDT` 같은 심볼에서도 `USDT`는 끝에만 나타난다.

### 초기 스냅샷

REST 응답의 `lastPrice`와 `priceChangePercent`를 즉시 Redis에 저장하여, WebSocket 연결 전에도 시세를 제공한다.

```
ExchangeInitializer.loadBinance():
  1. REST 호출 → USDT 필터 → MarketInfoCache 적재
  2. 각 항목을 NormalizedTicker로 변환 → TickerRedisRepository.save()
  3. RealtimePriceCollector.connectBinance()
```

순서가 중요하다: 캐시 적재 + Redis 초기 스냅샷 저장이 완료된 후 WebSocket을 연결한다.

---

## WebSocket API

### 연결 정보

- **URL:** `wss://stream.binance.com:9443/ws/!miniTicker@arr`
- **프레임 유형:** 텍스트
- **구독 메시지:** 불필요 — URL 자체가 스트림을 지정
- **수신 주기:** ~1초마다 전체 심볼 배열 전송

> **`!ticker@arr` → `!miniTicker@arr` 전환:** `!ticker@arr` 스트림은 2026-03-26 폐기 예정이다. 공식 권장 대체 스트림인 `!miniTicker@arr`를 사용한다. `!miniTicker@arr`에는 `P`(priceChangePercent) 필드가 없으므로 `(c - o) / o`로 changeRate를 직접 계산한다.

### !miniTicker@arr 스트림

별도의 구독 메시지 없이 연결하면 자동으로 모든 심볼의 미니 티커가 배열로 수신된다. `!ticker@arr`보다 필드가 적어 대역폭이 절감된다.

### 응답 필드

| 필드 | 타입 | 설명 |
|------|------|------|
| `e` | String | 이벤트 타입 ("24hrMiniTicker") |
| `E` | Number | 이벤트 시각 (epoch ms) |
| `s` | String | 심볼 ("BTCUSDT") |
| `c` | String | 최종가 (종가) |
| `o` | String | 시가 |
| `h` | String | 고가 |
| `l` | String | 저가 |
| `v` | String | 기축 자산 거래량 |
| `q` | String | 호가 자산 거래대금 |

### 응답 예시

```json
[
  {
    "e": "24hrMiniTicker",
    "E": 1676965262000,
    "s": "BTCUSDT",
    "c": "32287.00000000",
    "o": "31886.50000000",
    "h": "32310.00000000",
    "l": "31855.00000000",
    "v": "24500.12300000",
    "q": "786543210.50000000"
  }
]
```

### 사용 필드 (5개만 역직렬화)

| 필드 | NormalizedTicker 매핑 |
|------|----------------------|
| `s` | base 추출 (`symbol.replace("USDT", "")`) |
| `c` | `lastPrice` (String → BigDecimal) |
| `o` | `changeRate` 계산에 사용: `(c - o) / o` |
| `q` | `quoteTurnover` (String → BigDecimal) |
| `E` | 무시, `System.currentTimeMillis()`로 `tsMs` 설정 |

### changeRate 계산

`!miniTicker@arr`에는 `P`(priceChangePercent) 필드가 없다. 시가(`o`)와 종가(`c`)를 사용하여 직접 계산한다.

```java
BigDecimal close = new BigDecimal(lastPrice);   // c
BigDecimal open = new BigDecimal(openPrice);     // o
BigDecimal changeRate = BigDecimal.ZERO;
if (open.compareTo(BigDecimal.ZERO) != 0) {
    changeRate = close.subtract(open)
        .divide(open, 8, RoundingMode.HALF_UP);
}
// open=31886.50, close=32287.00 → (32287-31886.5)/31886.5 ≈ 0.01256482
```

### USDT 필터링

`!miniTicker@arr`는 **모든 심볼**을 전송한다 (2000+ 개). USDT 마켓만 처리해야 한다.

필터링 방법: `MarketInfoCache`에 USDT 마켓만 적재했으므로, 캐시에 존재하는 심볼만 처리한다.

```java
cache.find(Exchange.BINANCE, message.symbol())  // 캐시에 없으면 무시
    .ifPresent(metadata -> {
        NormalizedTicker ticker = message.toNormalized(metadata.displayName());
        tickerRedisRepository.save(ticker).subscribe();
    });
```

### 배열 배치 처리

WebSocket이 ~1초마다 전체 심볼 배열을 전송하므로, Redis 쓰기를 bounded concurrency로 처리한다.

```java
Flux.fromArray(messages)
    .filter(m -> cache.find(Exchange.BINANCE, m.symbol()).isPresent())
    .flatMap(m -> {
        MarketInfo meta = cache.find(Exchange.BINANCE, m.symbol()).orElseThrow();
        return tickerRedisRepository.save(m.toNormalized(meta.displayName()));
    }, 32)  // bounded concurrency 32
    .then();
```

---

## 구현 클래스 목록

| 클래스 | 패키지 | 역할 |
|--------|--------|------|
| `BinanceRestClient` | `client.rest` | WebClient로 24hr 티커 조회, USDT 필터링, `MarketInfo` 리스트 + 초기 스냅샷 데이터 반환 |
| `BinanceTickerResponse` | `client.rest.dto` | REST 응답 역직렬화 record |
| `BinanceTickerMessage` | `client.websocket.dto` | WebSocket 메시지 역직렬화 record, `toNormalized(String displayName)` 포함 |
| `BinanceWebSocketHandler` | `client.websocket` | `ExchangeTickerStream` 구현, 배열 배치 처리, 캐시 기반 필터링, 재연결 |

### BinanceRestClient

```java
@Component
public class BinanceRestClient {
    private final WebClient webClient;

    @Value("${exchange.binance.rest-url}") String restUrl;

    public Flux<BinanceTickerResponse> fetchUsdtTickers() {
        return webClient.get()
            .uri(restUrl)
            .retrieve()
            .bodyToFlux(BinanceTickerResponse.class)
            .filter(r -> r.symbol().endsWith("USDT"));
    }
}
```

> `BinanceRestClient`는 `MarketInfo`가 아닌 `BinanceTickerResponse`를 반환한다. 초기 스냅샷을 위해 `lastPrice`, `priceChangePercent` 등의 원본 데이터가 필요하기 때문이다. `MarketInfo` 변환과 Redis 저장은 `ExchangeInitializer`에서 수행한다.

### BinanceTickerMessage

`!miniTicker@arr` 스트림 대응. `P` 필드가 없으므로 `o`(시가)와 `c`(종가)로 changeRate를 직접 계산한다.

```java
public record BinanceTickerMessage(
    @JsonProperty("s") String symbol,
    @JsonProperty("c") String lastPrice,
    @JsonProperty("o") String openPrice,
    @JsonProperty("q") String quoteVolume,
    @JsonProperty("E") long eventTime
) {
    public NormalizedTicker toNormalized(String displayName) {
        String base = symbol.replace("USDT", "");
        BigDecimal close = new BigDecimal(lastPrice);
        BigDecimal open = new BigDecimal(openPrice);
        BigDecimal changeRate = BigDecimal.ZERO;
        if (open.compareTo(BigDecimal.ZERO) != 0) {
            changeRate = close.subtract(open)
                .divide(open, 8, RoundingMode.HALF_UP);
        }
        return new NormalizedTicker(
            Exchange.BINANCE.name(),
            base, "USDT", displayName,
            close,
            changeRate,
            new BigDecimal(quoteVolume),
            System.currentTimeMillis()
        );
    }
}
```

### BinanceWebSocketHandler

핵심 로직:

1. `ReactorNettyWebSocketClient`로 WebSocket 연결 (구독 메시지 불필요)
2. 수신된 텍스트 프레임을 `BinanceTickerMessage[]` 배열로 역직렬화
3. `MarketInfoCache` 기반 USDT 필터링 (캐시에 없는 심볼 무시)
4. `toNormalized()` → `TickerRedisRepository.save()` (bounded concurrency 32)
5. 연결 끊김 시 `retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(60)))` 재연결

### 업비트/빗썸과의 차이점

| 항목 | 업비트/빗썸 | 바이낸스 |
|------|------------|---------|
| 구독 방식 | 구독 메시지 전송 필요 | URL로 스트림 지정 (구독 불필요) |
| 수신 형식 | 개별 티커 객체 | 티커 배열 (~1초 주기) |
| 필터링 | 구독 시 코드 지정 | 수신 후 캐시 기반 필터링 |
| 변동률 | 비율 (0.0123) | `(c - o) / o`로 직접 계산 (`!miniTicker@arr`에 `P` 필드 없음) |
| 가격 타입 | BigDecimal | String → BigDecimal 변환 필요 |
| 프레임 유형 | 바이너리(업비트) / 텍스트(빗썸) | 텍스트 |
| 초기 스냅샷 | 불필요 | REST 응답으로 Redis 초기 적재 |
