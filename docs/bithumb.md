# 빗썸 (feature/collector-bithumb)

## REST API

### 마켓 목록 조회

- **URL:** `GET https://api.bithumb.com/v1/market/all?isDetails=false`
- **인증:** 불필요 (PUBLIC API)
- **필터:** `market`이 `KRW-`로 시작하는 항목만 사용
- **응답 형식:** 업비트와 동일

**응답 예시:**

```json
[
  {
    "market": "KRW-BTC",
    "korean_name": "비트코인",
    "english_name": "Bitcoin"
  },
  {
    "market": "KRW-ETH",
    "korean_name": "이더리움",
    "english_name": "Ethereum"
  }
]
```

**DTO:**

```java
// client/rest/dto/BithumbMarketResponse.java (UpbitMarketResponse와 동일 구조)
public record BithumbMarketResponse(
    String market,
    @JsonProperty("korean_name") String koreanName,
    @JsonProperty("english_name") String englishName
) {}
```

> 빗썸 v1 API는 업비트의 API 형식을 미러링한다. REST 응답 구조와 WebSocket 메시지 형식이 모두 동일하다.

**MarketInfo 변환:**

```
market: "KRW-BTC"
→ base: "BTC"          (market.substring(4))
→ quote: "KRW"
→ pair: "BTC/KRW"
→ displayName: "비트코인" (koreanName)
→ 캐시 키: "BITHUMB:KRW-BTC"
```

---

## WebSocket API

### 연결 정보

- **URL:** `wss://pubwss.bithumb.com/pub/ws`
- **프레임 유형:** 텍스트 (업비트와 달리 바이너리/gzip이 아님)
- **구독 메시지:** 연결 직후 JSON 배열로 전송

### 구독 메시지 포맷

```json
[
  {"ticket": "trypto-collector"},
  {"type": "ticker", "codes": ["KRW-BTC", "KRW-ETH", "KRW-XRP"]}
]
```

업비트와 동일한 형식이다.

- `ticket`: 식별 문자열
- `codes`: `MarketInfoCache.getSymbolCodes(Exchange.BITHUMB)`에서 조회

### 응답 필드

업비트 WebSocket 응답과 동일한 필드명, 동일한 구조를 사용한다. 상세 필드 목록은 `docs/upbit.md`를 참조한다.

### 사용 필드 (5개만 역직렬화)

| 필드 | NormalizedTicker 매핑 |
|------|----------------------|
| `code` | base 추출 (`code.substring(4)`) |
| `trade_price` | `lastPrice` |
| `signed_change_rate` | `changeRate` (이미 비율) |
| `acc_trade_price_24h` | `quoteTurnover` |
| `timestamp` | 무시, `System.currentTimeMillis()`로 `tsMs` 설정 |

### 업비트와의 차이점

| 항목 | 업비트 | 빗썸 |
|------|--------|------|
| WebSocket URL | `wss://api.upbit.com/websocket/v1` | `wss://pubwss.bithumb.com/pub/ws` |
| 프레임 유형 | 바이너리 (gzip 가능) | 텍스트 |
| 구독 형식 | 동일 | 동일 |
| 응답 필드 | 동일 | 동일 |
| 역직렬화 DTO | UpbitTickerMessage | BithumbTickerMessage (동일 구조) |

핵심 차이는 **프레임 유형**뿐이다. 빗썸은 텍스트 프레임이므로 gzip 압축 해제가 필요 없다.

---

## 구현 클래스 목록

| 클래스 | 패키지 | 역할 |
|--------|--------|------|
| `BithumbRestClient` | `client.rest` | WebClient로 마켓 목록 조회, KRW- 필터링, `MarketInfo` 리스트 반환 |
| `BithumbTickerMessage` | `client.websocket.dto` | WebSocket 메시지 역직렬화 record, `toNormalized(String displayName)` 포함 |
| `BithumbWebSocketHandler` | `client.websocket` | `ExchangeTickerStream` 구현, 텍스트 프레임 처리, 구독/재연결 |

### BithumbRestClient

```java
@Component
public class BithumbRestClient {
    private final WebClient webClient;

    @Value("${exchange.bithumb.rest-url}") String restUrl;

    public Flux<MarketInfo> fetchKrwMarkets() {
        return webClient.get()
            .uri(restUrl)
            .retrieve()
            .bodyToFlux(BithumbMarketResponse.class)
            .filter(r -> r.market().startsWith("KRW-"))
            .map(r -> new MarketInfo(
                r.market().substring(4),
                "KRW",
                r.market().substring(4) + "/KRW",
                r.koreanName()
            ));
    }
}
```

### BithumbTickerMessage

```java
public record BithumbTickerMessage(
    @JsonProperty("code") String code,
    @JsonProperty("trade_price") BigDecimal tradePrice,
    @JsonProperty("signed_change_rate") BigDecimal signedChangeRate,
    @JsonProperty("acc_trade_price_24h") BigDecimal accTradePrice24h,
    @JsonProperty("timestamp") long timestamp
) {
    public NormalizedTicker toNormalized(String displayName) {
        String base = code.substring(4);
        return new NormalizedTicker(
            Exchange.BITHUMB.name(),
            base, "KRW", displayName,
            tradePrice,
            signedChangeRate,
            accTradePrice24h,
            System.currentTimeMillis()
        );
    }
}
```

> `UpbitTickerMessage`와 구조가 동일하지만, `Exchange.BITHUMB`을 사용하므로 별도 record로 분리한다.

### BithumbWebSocketHandler

핵심 로직:

1. `ReactorNettyWebSocketClient`로 WebSocket 연결
2. 연결 직후 구독 메시지(JSON 배열) 전송 — 업비트와 동일 형식
3. 수신된 **텍스트 프레임**을 바로 파싱 (gzip 해제 불필요)
4. JSON → `BithumbTickerMessage` 역직렬화
5. `MarketInfoCache`에서 displayName 조회
6. `toNormalized()` → `TickerRedisRepository.save()`
7. 연결 끊김 시 `retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(60)))` 재연결

업비트 WebSocketHandler와 거의 동일하며, 바이너리 프레임 처리(gzip 해제) 로직만 없다.
