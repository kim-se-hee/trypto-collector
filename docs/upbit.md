# 업비트 (feature/collector-upbit)

## REST API

### 마켓 목록 조회

- **URL:** `GET https://api.upbit.com/v1/market/all`
- **인증:** 불필요 (QUOTATION API는 공개)
- **필터:** `market`이 `KRW-`로 시작하는 항목만 사용

**응답 예시:**

```json
[
  {
    "market": "KRW-BTC",
    "korean_name": "비트코인",
    "english_name": "Bitcoin",
    "market_warning": "NONE"
  },
  {
    "market": "KRW-ETH",
    "korean_name": "이더리움",
    "english_name": "Ethereum",
    "market_warning": "NONE"
  }
]
```

**DTO:**

```java
// client/rest/dto/UpbitMarketResponse.java
public record UpbitMarketResponse(
    String market,
    @JsonProperty("korean_name") String koreanName,
    @JsonProperty("english_name") String englishName
) {}
```

**MarketInfo 변환:**

```
market: "KRW-BTC"
→ base: "BTC"          (market.substring(4))
→ quote: "KRW"
→ pair: "BTC/KRW"
→ displayName: "비트코인" (koreanName)
→ 캐시 키: "UPBIT:KRW-BTC"
```

---

## WebSocket API

### 연결 정보

- **URL:** `wss://api.upbit.com/websocket/v1`
- **프레임 유형:** 바이너리 (gzip 압축 가능)
- **구독 메시지:** 연결 직후 JSON 배열로 전송

### 구독 메시지 포맷

```json
[
  {"ticket": "trypto-collector"},
  {"type": "ticker", "codes": ["KRW-BTC", "KRW-ETH", "KRW-XRP"]}
]
```

- `ticket`: 식별 문자열 (임의의 고유 값)
- `codes`: `MarketInfoCache.getSymbolCodes(Exchange.UPBIT)`에서 조회

### 바이너리 프레임 처리

업비트는 WebSocket 응답을 **바이너리 프레임**으로 전송한다. gzip으로 압축된 경우가 있으므로 매직 넘버를 확인하여 압축 해제해야 한다.

```java
private byte[] decompressIfNeeded(ByteBuffer buffer) {
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    if (bytes.length > 2 && bytes[0] == (byte) 0x1f && bytes[1] == (byte) 0x8b) {
        // gzip 매직 넘버 감지 → GZIPInputStream으로 압축 해제
        return decompress(bytes);
    }
    return bytes;
}
```

### 응답 필드 (전체)

| 필드 | 약어 | 타입 | 설명 |
|------|------|------|------|
| type | ty | String | "ticker" |
| code | cd | String | "KRW-BTC" |
| opening_price | op | Double | 시가 |
| high_price | hp | Double | 고가 |
| low_price | lp | Double | 저가 |
| trade_price | tp | Double | 현재가 (최종 체결가) |
| prev_closing_price | pcp | Double | 전일 종가 |
| change | c | String | RISE/EVEN/FALL |
| change_price | cp | Double | 변동 금액 (부호 없음) |
| signed_change_price | scp | Double | 부호 있는 변동 금액 |
| change_rate | cr | Double | 변동률 (부호 없음) |
| signed_change_rate | scr | Double | 부호 있는 변동률 (비율, 0.0127 = +1.27%) |
| trade_volume | tv | Double | 최근 체결량 |
| acc_trade_volume | atv | Double | 누적 거래량 (UTC 0시 기준) |
| acc_trade_volume_24h | atv24h | Double | 24시간 누적 거래량 |
| acc_trade_price | atp | Double | 누적 거래대금 (UTC 0시 기준) |
| acc_trade_price_24h | atp24h | Double | 24시간 누적 거래대금 |
| trade_date | tdt | String | yyyyMMdd |
| trade_time | ttm | String | HHmmss |
| trade_timestamp | ttms | Long | 체결 타임스탬프 (ms) |
| ask_bid | ab | String | ASK/BID |
| acc_ask_volume | aav | Double | 누적 매도량 |
| acc_bid_volume | abv | Double | 누적 매수량 |
| highest_52_week_price | h52wp | Double | 52주 최고가 |
| highest_52_week_date | h52wdt | String | yyyy-MM-dd |
| lowest_52_week_price | l52wp | Double | 52주 최저가 |
| lowest_52_week_date | l52wdt | String | yyyy-MM-dd |
| market_state | ms | String | PREVIEW/ACTIVE/DELISTED |
| is_trading_suspended | its | Boolean | 거래 정지 여부 |
| delisting_date | dd | Date | 상장 폐지일 |
| market_warning | mw | String | NONE/CAUTION |
| timestamp | tms | Long | 서버 타임스탬프 (ms) |
| stream_type | st | String | SNAPSHOT/REALTIME |

### 응답 예시

```json
{
  "type": "ticker",
  "code": "KRW-BTC",
  "opening_price": 31883000,
  "high_price": 32310000,
  "low_price": 31855000,
  "trade_price": 32287000,
  "prev_closing_price": 31883000.00000000,
  "acc_trade_price": 78039261076.51241000,
  "change": "RISE",
  "change_price": 404000.00000000,
  "signed_change_price": 404000.00000000,
  "change_rate": 0.0126713295,
  "signed_change_rate": 0.0126713295,
  "ask_bid": "ASK",
  "trade_volume": 0.03103806,
  "acc_trade_volume": 2429.58834336,
  "trade_date": "20230221",
  "trade_time": "074102",
  "trade_timestamp": 1676965262139,
  "acc_ask_volume": 1146.25573608,
  "acc_bid_volume": 1283.33260728,
  "highest_52_week_price": 57678000.00000000,
  "highest_52_week_date": "2022-03-28",
  "lowest_52_week_price": 20700000.00000000,
  "lowest_52_week_date": "2022-12-30",
  "market_state": "ACTIVE",
  "is_trading_suspended": false,
  "delisting_date": null,
  "market_warning": "NONE",
  "timestamp": 1676965262177,
  "acc_trade_price_24h": 228827082483.70729000,
  "acc_trade_volume_24h": 7158.80283560,
  "stream_type": "REALTIME"
}
```

### 사용 필드 (5개만 역직렬화)

나머지 필드는 Jackson `FAIL_ON_UNKNOWN_PROPERTIES = false` 설정으로 무시된다.

| 필드 | NormalizedTicker 매핑 |
|------|----------------------|
| `code` | base 추출 (`code.substring(4)`) |
| `trade_price` | `lastPrice` |
| `signed_change_rate` | `changeRate` (이미 비율) |
| `acc_trade_price_24h` | `quoteTurnover` |
| `timestamp` | 무시, `System.currentTimeMillis()`로 `tsMs` 설정 |

---

## 구현 클래스 목록

| 클래스 | 패키지 | 역할 |
|--------|--------|------|
| `UpbitRestClient` | `client.rest` | WebClient로 마켓 목록 조회, KRW- 필터링, `MarketInfo` 리스트 반환 |
| `UpbitTickerMessage` | `client.websocket.dto` | WebSocket 메시지 역직렬화 record, `toNormalized(String displayName)` 포함 |
| `UpbitWebSocketHandler` | `client.websocket` | `ExchangeTickerStream` 구현, 바이너리 프레임 처리, gzip 해제, 구독/재연결 |

### UpbitRestClient

```java
@Component
public class UpbitRestClient {
    private final WebClient webClient;

    @Value("${exchange.upbit.rest-url}") String restUrl;

    public Flux<MarketInfo> fetchKrwMarkets() {
        return webClient.get()
            .uri(restUrl)
            .retrieve()
            .bodyToFlux(UpbitMarketResponse.class)
            .filter(r -> r.market().startsWith("KRW-"))
            .map(r -> new MarketInfo(
                r.market().substring(4),  // base
                "KRW",                    // quote
                r.market().substring(4) + "/KRW",  // pair
                r.koreanName()            // displayName
            ));
    }
}
```

### UpbitTickerMessage

```java
public record UpbitTickerMessage(
    @JsonProperty("code") String code,
    @JsonProperty("trade_price") BigDecimal tradePrice,
    @JsonProperty("signed_change_rate") BigDecimal signedChangeRate,
    @JsonProperty("acc_trade_price_24h") BigDecimal accTradePrice24h,
    @JsonProperty("timestamp") long timestamp
) {
    public NormalizedTicker toNormalized(String displayName) {
        String base = code.substring(4);  // "KRW-BTC" → "BTC"
        return new NormalizedTicker(
            Exchange.UPBIT.name(),
            base, "KRW", displayName,
            tradePrice,
            signedChangeRate,
            accTradePrice24h,
            System.currentTimeMillis()
        );
    }
}
```

### UpbitWebSocketHandler

핵심 로직:

1. `ReactorNettyWebSocketClient`로 WebSocket 연결
2. 연결 직후 구독 메시지(JSON 배열) 전송
3. 수신된 바이너리 프레임을 `decompressIfNeeded()`로 처리
4. JSON → `UpbitTickerMessage` 역직렬화
5. `MarketInfoCache`에서 displayName 조회
6. `toNormalized()` → `TickerRedisRepository.save()`
7. 연결 끊김 시 `retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(60)))` 재연결
