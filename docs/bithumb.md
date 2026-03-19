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

- **URL:** `wss://ws-api.bithumb.com/websocket/v1`
- **프레임 유형:** 바이너리 (gzip 압축 없음)
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
| WebSocket URL | `wss://api.upbit.com/websocket/v1` | `wss://ws-api.bithumb.com/websocket/v1` |
| 프레임 유형 | 바이너리 (gzip 가능) | 바이너리 (gzip 없음) |
| 구독 형식 | 동일 | 동일 |
| 응답 필드 | 동일 | 동일 |
| 역직렬화 DTO | UpbitTickerMessage | BithumbTickerMessage (동일 구조) |

핵심 차이는 **프레임 유형**뿐이다. 빗썸은 바이너리 프레임이지만 gzip 압축은 없다.

