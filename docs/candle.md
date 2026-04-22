# 캔들 데이터

## 개요

사용자가 차트에서 과거 캔들 데이터를 조회할 수 있어야 한다. 수집기는 매 시세 tick을 InfluxDB `ticker_raw` measurement에 저장하고, InfluxDB Task가 서버 사이드에서 캔들(OHLC)로 집계한다.

## 데이터 흐름

```
WebSocket 시세 수신 (기존 파이프라인)
    │
    ▼
NormalizedTicker
    │
    ├── Redis 저장 (기존)
    ├── RabbitMQ 발행 (기존)
    └── TickRawWriter → InfluxDB ticker_raw
            │
            ▼
    InfluxDB Task (서버 사이드 집계)
        ticker_raw → candle_1m (매 1분)
        candle_1m  → candle_5m (매 5분)
        candle_1m  → candle_1h (매 1시간)
        candle_1h  → candle_4h (매 4시간)
        candle_1h  → candle_1d (매 1일)
        candle_1d  → candle_1w (매 1주, 월요일 시작)
        candle_1d  → candle_1M (매 1개월, 1일 시작)
```

---

## InfluxDB 스키마

### Raw tick (ticker_raw)

`TickRawWriter`가 매 시세 수신 시 기록한다.

| 구분 | 이름 | 설명 | 예시 |
|------|------|------|------|
| tag | `exchange` | 거래소 | `UPBIT`, `BINANCE` |
| tag | `symbol` | 거래 페어 | `BTC/KRW`, `ETH/USDT` |
| field | `price` | 현재가 (double) | |
| timestamp | | 수집 시각 | epoch ms |

### 캔들 (candle_1m, candle_5m, candle_1h, ...)

InfluxDB Task가 집계한다. 모든 캔들 measurement의 구조는 동일하다.

| 구분 | 이름 | 설명 | 예시 |
|------|------|------|------|
| tag | `exchange` | 거래소 | `UPBIT`, `BINANCE` |
| tag | `symbol` | 거래 페어 | `BTC/KRW`, `ETH/USDT` |
| field | `open` | 시가 | |
| field | `high` | 고가 | |
| field | `low` | 저가 | |
| field | `close` | 종가 | |
| timestamp | | 해당 구간의 시작 시각 | `2026-03-18T10:04:00Z` |

---

## InfluxDB Task

계단식 집계로 상위 타임프레임을 생성한다. 각 Task는 바로 아래 단계의 measurement를 원본으로 사용하여 계산량을 최소화한다.

```
ticker_raw → candle_1m (매 1분)
candle_1m  → candle_5m (매 5분)
candle_1m  → candle_1h (매 1시간)
candle_1h  → candle_4h (매 4시간)
candle_1h  → candle_1d (매 1일)
candle_1d  → candle_1w (매 1주, 월요일 시작)
candle_1d  → candle_1M (매 1개월, 1일 시작)
```

| measurement | source | 집계 주기 | 집계 방식 | 비고 |
|-------------|--------|-----------|-----------|------|
| `candle_1m` | `ticker_raw` | 1분 | `first(price)`, `max(price)`, `min(price)`, `last(price)` | raw tick에서 직접 집계 |
| `candle_5m` | `candle_1m` | 5분 | `first(open)`, `max(high)`, `min(low)`, `last(close)` | |
| `candle_1h` | `candle_1m` | 1시간 | 동일 | |
| `candle_4h` | `candle_1h` | 4시간 | 동일 | |
| `candle_1d` | `candle_1h` | 1일 | 동일 | |
| `candle_1w` | `candle_1d` | 1주 | 동일 | `offset: 4d`로 월요일 시작 |
| `candle_1M` | `candle_1d` | 1개월 | 동일 | calendar duration |

Task 정의는 `influxdb/init-tasks.sh`에 있으며, Docker 초기 setup 시 자동 생성된다.

### 실행 순서

Task 간 offset 체인으로 이전 단계의 write 완료를 보장한다.

| Task | offset | 이유 |
|------|--------|------|
| `candle_1m` | 10s | `TickRawWriter`의 write 전파 대기 |
| `candle_5m` | 30s | `candle_1m` Task 완료 대기 |
| `candle_1h` | 1m | `candle_1m` Task 완료 대기 |
| `candle_4h`, `candle_1d` | 2m | `candle_1h` Task 완료 대기 |
| `candle_1w`, `candle_1M` | 3m | `candle_1d` Task 완료 대기 |

---

## 조회

트레이딩 서버에서 캔들 데이터는 InfluxDB에서 직접 조회한다. 별도 캐싱 레이어를 두지 않는다.
