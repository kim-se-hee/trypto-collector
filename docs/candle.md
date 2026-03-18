# 캔들 데이터 수집

## 문제 정의

- 사용자가 차트를 스와이프하면서 과거 캔들 데이터를 조회할 수 있어야 한다.
- 매번 거래소 REST API를 호출하면 Rate Limit에 걸린다.
- 거래소마다 캔들 WebSocket 지원이 다르다: 바이낸스는 분봉 WebSocket을 제공하지만, 업비트는 초봉만, 빗썸은 캔들 WebSocket이 없다.
- 따라서 내부에서 캔들 데이터를 직접 생성해야 한다.

## 수집 전략

기존 시세 수집 파이프라인이 WebSocket으로 받는 현재가(`lastPrice`)를 활용하여 1분봉을 직접 생성한다. 분봉만 직접 생성하고, 상위 타임프레임(1시간/4시간/일/주/월봉)은 InfluxDB Continuous Query로 자동 집계한다.

## 데이터 흐름

```
WebSocket 시세 수신 (기존 파이프라인)
    │
    ▼
NormalizedTicker
    │
    ├── Redis 저장 (기존)
    ├── RabbitMQ 발행 (기존)
    └── 인메모리 분봉 버퍼 갱신 (NEW)
            │
            ▼
    1분 주기로 완성된 분봉 추출 + 버퍼 초기화
            │
            ▼
    InfluxDB 배치 write
            │
            ▼
    Flux Task 계단식 집계
        candle_1m → candle_1h
                     ├── candle_4h
                     └── candle_1d
                          ├── candle_1w (월요일 시작)
                          └── candle_1M (1일 시작)
```

---

## 분봉 생성 규칙

### OHLC 추적

코인별로 1분 주기 동안 들어오는 현재가(`lastPrice`)를 기준으로 OHLC를 추적한다.

| 필드 | 규칙 |
|------|------|
| 시가(open) | 해당 분의 첫 번째 현재가 |
| 고가(high) | 해당 분의 최대 현재가 |
| 저가(low) | 해당 분의 최소 현재가 |
| 종가(close) | 해당 분의 마지막 현재가 |

### 버퍼 식별 키

거래소와 거래 페어의 조합으로 코인을 식별한다: `"{EXCHANGE}:{BASE}/{QUOTE}"` (예: `"UPBIT:BTC/KRW"`, `"BINANCE:BTC/USDT"`)

### 플러시 주기

1분마다 버퍼에서 완성된 분봉을 꺼내 InfluxDB에 배치 write하고 버퍼를 초기화한다. 버퍼가 비어 있으면 아무 작업도 하지 않는다.

### 타임스탬프 결정

flush 시점에서 1분을 뺀 분의 시작 시각을 사용한다. 예: 10:05:00에 flush하면 타임스탬프는 `10:04:00`이다.

### 에러 처리

- InfluxDB write 실패 시 해당 분봉은 유실된다. 재시도하지 않는다.
- 분봉 생성 실패가 시세 수집 파이프라인(Redis 저장, RabbitMQ 발행)을 중단해서는 안 된다.

---

## InfluxDB 스키마

### 분봉 (candle_1m)

| 구분 | 이름 | 설명 | 예시 |
|------|------|------|------|
| tag | `exchange` | 거래소 | `UPBIT`, `BINANCE` |
| tag | `coin` | 거래 페어 | `BTC/KRW`, `ETH/USDT` |
| field | `open` | 시가 | |
| field | `high` | 고가 | |
| field | `low` | 저가 | |
| field | `close` | 종가 | |
| timestamp | | 해당 분의 시작 시각 | `2026-03-18T10:04:00Z` |

### Flux Task (상위 타임프레임 집계)

계단식 집계로 상위 타임프레임을 생성한다. 각 Task는 바로 아래 단계의 measurement를 원본으로 사용하여 계산량을 최소화한다.

```
candle_1m → candle_1h (매 1시간)
candle_1h → candle_4h (매 4시간)
candle_1h → candle_1d (매 1일)
candle_1d → candle_1w (매 1주, 월요일 시작)
candle_1d → candle_1M (매 1개월, 1일 시작)
```

| measurement | source | 집계 주기 | 집계 방식 | 비고 |
|-------------|--------|-----------|-----------|------|
| `candle_1h` | `candle_1m` | 1시간 | `first(open)`, `max(high)`, `min(low)`, `last(close)` | |
| `candle_4h` | `candle_1h` | 4시간 | 동일 | |
| `candle_1d` | `candle_1h` | 1일 | 동일 | |
| `candle_1w` | `candle_1d` | 1주 | 동일 | `offset: 4d`로 월요일 시작 |
| `candle_1M` | `candle_1d` | 1개월 | 동일 | calendar duration |

Task 정의는 `influxdb/init-tasks.sh`에 있으며, Docker 초기 setup 시 자동 생성된다.

#### 실행 순서

Task 간 offset 체인으로 이전 단계의 write 완료를 보장한다.

| Task | offset | 이유 |
|------|--------|------|
| `candle_1h` | 1m | `CandleFlushScheduler`(매분 :00)의 write 완료 대기 |
| `candle_4h`, `candle_1d` | 2m | `candle_1h` Task 완료 대기 |
| `candle_1w`, `candle_1M` | 3m | `candle_1d` Task 완료 대기 |

### 조회

트레이딩 서버에서 캔들 데이터는 InfluxDB에서 직접 조회한다. 별도 캐싱 레이어를 두지 않는다.

### 스레드 분리

- InfluxDB write는 시세 수신 스레드와 별도 스레드에서 수행한다. 시세 수신에 영향을 주지 않아야 한다.
