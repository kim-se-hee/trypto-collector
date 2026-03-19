# 아키텍처

## 개요

trypto-collector는 업비트, 빗썸, 바이낸스 세 거래소의 실시간 시세를 수집하여 Redis에 저장하고, RabbitMQ로 시세 변경 이벤트를 발행하며, 인메모리에서 1분봉을 생성하여 InfluxDB에 저장하는 수집기다. 백엔드(trypto-backend)는 Redis에서 시세를 조회하여 수익률 계산 등에 활용하고, RabbitMQ 이벤트를 수신하여 미체결 주문 매칭에, InfluxDB에서 캔들 데이터를 조회하여 차트 표시에 활용한다.

외부 API → 정규화 → Redis, InfluxDB 저장의 단방향 파이프라인이다.

## 데이터 흐름

```
Application Startup
    │
    ▼
ExchangeInitializer (병렬 초기화, 실패 격리)
    ├── Upbit REST → KRW- 필터 → MarketInfoCache 적재 + Redis 메타데이터 저장 → UpbitWebSocketHandler.connect()
    ├── Bithumb REST → KRW- 필터 → MarketInfoCache 적재 + Redis 메타데이터 저장 → BithumbWebSocketHandler.connect()
    └── Binance REST → USDT 필터 → MarketInfoCache 적재 + Redis 초기 스냅샷 저장 + Redis 메타데이터 저장 → BinanceWebSocketHandler.connect()
            │
            ▼
WebSocket 시세 수신
    ├── UpbitWebSocketHandler.connect()
    │     → 구독 메시지 전송 → 바이너리 프레임 수신 → gzip 해제 → UpbitTickerMessage → toNormalized() → TickerSinkProcessor.process()
    ├── BithumbWebSocketHandler.connect()
    │     → 구독 메시지 전송 → 텍스트 프레임 수신 → BithumbTickerMessage → toNormalized() → TickerSinkProcessor.process()
    └── BinanceWebSocketHandler.connect()
          → 배열 배치 수신 → 캐시 기반 USDT 필터링 → BinanceTickerMessage → toNormalized() → TickerSinkProcessor.process()
            │
            ├──────────────────────────────────┬──────────────────────────┐
            ▼                                  ▼                          ▼
Redis                                  RabbitMQ (Fanout Exchange)   CandleBuffer (인메모리 OHLC)
    ┌ ticker:{EXCHANGE}:{BASE}/{QUOTE}         │                          │
    │   Value: JSON NormalizedTicker     ┌─────┼─────┐              @Scheduled (1분)
    │   TTL: 30초                     Server A  Server B  Server C        │
    │                                  (미체결 주문 매칭)                  ▼
    ├ market-meta:{EXCHANGE}                                       InfluxDB (배치 write)
    │   Value: JSON MarketInfo[]                                     candle_1m → CQ → 1h/4h/1d/1w/1M
    │   TTL: 없음
    │
    ▼
trypto-backend
    ├── Redis에서 시세 조회
    └── Redis에서 마켓 메타데이터 조회 → DB 저장
```

## 컴포넌트 역할

| 컴포넌트 | 역할 |
|----------|------|
| `ExchangeInitializer` | 애플리케이션 시작 시 각 거래소 REST API를 호출하여 마켓 메타데이터를 로딩하고, Redis에 저장한 뒤 WebSocket 연결을 트리거한다. 각 거래소는 독립적으로 로딩되어 하나가 실패해도 나머지에 영향이 없다. |
| `MarketInfoCache` | 인메모리 캐시. 거래소별 심볼 코드 → `MarketInfo` 매핑을 저장한다. WebSocket 핸들러가 displayName 조회 및 바이낸스 USDT 필터링에 사용한다. |
| `{거래소}RestClient` | 거래소 REST API를 호출하여 마켓 목록을 조회한다. KRW/USDT 마켓만 필터링한다. |
| `{거래소}WebSocketHandler` | 거래소 WebSocket에 연결하여 실시간 시세를 수신한다. 수신된 메시지를 `NormalizedTicker`로 변환하여 `TickerSinkProcessor`에 전달한다. 연결 끊김 시 지수 백오프로 재연결한다. |
| `TickerSinkProcessor` | `NormalizedTicker`를 받아 Redis 저장, RabbitMQ 이벤트 발행, CandleBuffer 갱신을 수행한다. 개별 싱크의 실패가 다른 싱크에 영향을 주지 않도록 격리한다. |
| `TickerEventPublisher` | `NormalizedTicker`를 `TickerEvent`로 변환하여 RabbitMQ Fanout Exchange에 발행한다. |
| `TickerRedisRepository` | `NormalizedTicker`를 JSON으로 직렬화하여 Redis에 저장한다. TTL 30초로 설정하여 WebSocket이 끊기면 자동 만료된다. |
| `MarketMetadataRedisRepository` | 거래소별 마켓 메타데이터(`MarketInfo` 목록)를 Redis에 JSON 배열로 저장한다. TTL 없이 영구 저장하여 백엔드가 기동 시 조회할 수 있다. |
| `CandleBuffer` | 인메모리 버퍼. 코인별로 1분 주기 동안의 OHLC를 추적한다. 시세 수신 시 갱신되고, 1분마다 flush된다. |
| `CandleFlushScheduler` | 1분마다 `CandleBuffer`에서 완성된 분봉을 꺼내 InfluxDB에 배치 write한다. |
| `RabbitMQConfig` | Fanout Exchange(`ticker.exchange`) 선언, Publisher Confirms 설정. |

## 설정 전략

`application.yml`에 모든 외부 설정을 관리하고, `@Value`로 주입한다. 세 거래소 모두 공개 API를 사용하므로 API 키가 필요 없다.

## 설계 결정

### RabbitMQ 시세 이벤트 발행

시세가 갱신될 때마다 RabbitMQ Fanout Exchange(`ticker.exchange`)로 시세 변경 이벤트를 발행한다. 트레이딩 서버(trypto-backend)는 이 이벤트를 수신하여 미체결 주문 매칭에 활용한다.

- **Publisher Confirms**: 브로커가 메시지를 수신했는지 확인한다
- **Fanout Exchange**: 모든 트레이딩 서버가 동일한 이벤트를 수신해야 하므로 Fanout을 사용한다. 큐 바인딩은 소비자(트레이딩 서버)가 담당한다

### 초기화 전략

각 거래소의 초기화(메타데이터 로딩 → WebSocket 연결)는 독립적으로 수행된다. 하나가 실패해도 나머지에 영향이 없으며, 실패 시 지수 백오프로 무한 재시도한다.

### 변동률(changeRate) 기준 차이

- 업비트/빗썸: `signed_change_rate`는 전일 종가 대비 변동률
- 바이낸스: `P`는 24시간 롤링 윈도우 대비 변동률

이 차이는 `NormalizedTicker`에서 별도 필드로 분리하지 않고, 소비자(백엔드)가 인지하도록 문서화한다.

### Redis TTL 30초

각 쓰기마다 TTL이 리셋된다. WebSocket이 끊겨 갱신이 중단되면 30초 후 키가 만료되어 소비자가 "시세 없음"을 인지할 수 있다.

### 마켓 메타데이터 Redis 저장

거래소별 상장 코인 목록(`MarketInfo`)을 Redis에 저장하여 백엔드(trypto-backend)가 기동 시 조회하여 DB에 저장할 수 있도록 한다.

- **키 포맷:** `market-meta:{EXCHANGE}` (예: `market-meta:UPBIT`)
- **값:** `MarketInfo` 배열의 JSON (예: `[{"base":"BTC","quote":"KRW","pair":"BTC/KRW","displayName":"비트코인"}, ...]`)
- **TTL 없음:** 메타데이터는 수집기가 재기동할 때마다 덮어쓴다. 수집기가 죽어도 마지막 메타데이터가 유지되어 백엔드가 참조할 수 있다
- **저장 시점:** `ExchangeInitializer`가 REST API로 마켓 목록을 로딩한 직후, WebSocket 연결 전에 저장한다
