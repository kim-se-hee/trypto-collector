# 아키텍처

## 개요

trypto-collector는 업비트, 빗썸, 바이낸스 세 거래소의 실시간 시세를 수집하여 Redis에 저장하고, RabbitMQ로 시세 변경 이벤트를 발행하며, InfluxDB에 raw tick을 저장하고, 매칭 엔진(trypto-engine)의 `engine.inbox` 큐로 tick을 발행하는 수집기다. 주문 매칭·체결·holding 갱신·DB 쓰기는 trypto-engine이 담당한다. 백엔드(trypto-api)는 Redis에서 시세를 조회하여 수익률 계산 등에 활용하고, 시세 이벤트를 수신하여 WebSocket 브로드캐스트에 활용한다. InfluxDB에서 캔들 데이터를 조회하여 차트 표시에 활용한다.

외부 API → 정규화 → Redis / InfluxDB / RabbitMQ / engine.inbox 저장의 단방향 팬아웃 파이프라인이다. 캔들 집계(1분봉, 5분봉)는 InfluxDB Task가 서버 사이드에서 수행한다.

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
            ├────────────────┬────────────────┬─────────────────────┐
            ▼                ▼                ▼                     ▼
       InfluxDB           Redis          RabbitMQ              RabbitMQ
       ticker_raw     ticker:{EX}:    [Fanout Exchange:     [Queue: engine.inbox]
       (raw tick)     {BASE}/{QUOTE}   ticker.exchange]           │
           │           TTL 30초             │                     ▼
           ▼                                ▼              trypto-engine
       InfluxDB Task              Server A/B/C                (매칭/체결/DB)
     candle_1m / 5m / 1h ...      (WebSocket                     │
                                  브로드캐스트)                   ▼
                                                           [Fanout: order.events]
                                                                  │
                                                                  ▼
                                                             trypto-api
                                                        (주문 상태 갱신)
```

## 컴포넌트 역할

| 컴포넌트 | 역할 |
|----------|------|
| `ExchangeInitializer` | 리더 획득 시 각 거래소 REST API를 호출하여 마켓 메타데이터와 초기 시세 스냅샷을 로딩하고 WebSocket 연결을 트리거한다. 리더십 상실 시 WebSocket 스레드를 정리한다. 각 거래소는 독립적으로 로딩되어 하나가 실패해도 나머지에 영향이 없다. |
| `MarketInfoCache` | 인메모리 캐시. 거래소별 심볼 코드 → `MarketInfo` 매핑을 저장한다. WebSocket 핸들러가 displayName 조회 및 바이낸스 USDT 필터링에 사용한다. |
| `{거래소}RestClient` | 거래소 REST API를 호출하여 마켓 목록을 조회한다. KRW/USDT 마켓만 필터링한다. |
| `{거래소}WebSocketHandler` | 거래소 WebSocket에 연결하여 실시간 시세를 수신한다. 수신된 메시지를 `NormalizedTicker`로 변환하여 `TickerSinkProcessor`에 전달한다. 연결 끊김 시 지수 백오프로 재연결하며, 재연결 실패가 지속되면 `RestPollingFallback`을 활성화한다. 리더십 상실 시 인터럽트 가드로 폴백 시작을 차단한다. |
| `TickerSinkProcessor` | `NormalizedTicker`를 받아 InfluxDB raw tick 기록 → Redis 저장 → RabbitMQ 시세 이벤트 발행 → engine.inbox tick 발행 순서로 팬아웃한다. 개별 싱크의 실패가 다른 싱크에 영향을 주지 않도록 격리한다. |
| `RestPollingFallback` | WebSocket 장애 시 거래소별 200ms 주기 REST 폴링으로 시세를 수집한다. WebSocket 복구 시 자동 중지된다. |
| `TickRawWriter` | 시세 tick을 InfluxDB `ticker_raw` measurement에 기록한다. InfluxDB Task가 이 데이터를 원본으로 캔들(OHLC)을 집계한다. |
| `TickerEventPublisher` | `NormalizedTicker`를 `TickerEvent`로 변환하여 RabbitMQ Fanout Exchange(`ticker.exchange`)에 발행한다. trypto-api가 WebSocket 브로드캐스트에 활용한다. |
| `EngineInboxPublisher` | `NormalizedTicker`를 tick 페이로드로 변환하여 RabbitMQ `engine.inbox` 큐에 발행한다. `event_type=TickReceived` 헤더를 붙인다. trypto-engine이 소비하여 미체결 주문 매칭 후 체결 처리를 수행한다. |
| `TickerRedisRepository` | `NormalizedTicker`를 JSON으로 직렬화하여 Redis에 저장한다. TTL 30초로 설정하여 WebSocket이 끊기면 자동 만료된다. |
| `MarketMetadataRedisRepository` | 거래소별 마켓 메타데이터(`MarketInfo` 목록)를 Redis에 JSON 배열로 저장한다. TTL 없이 영구 저장하여 백엔드가 기동 시 조회할 수 있다. |
| `LeaderElection` | Redisson 분산 락 기반 리더 선출. 5초 간격 갱신으로 단일 액티브 인스턴스를 보장한다. 리더십 변경 시 이벤트를 발행한다. |
| `LeaderLifecycleListener` | 리더십 이벤트를 수신하여 획득 시 거래소 초기화, 상실 시 거래소 스레드 정리를 수행한다. |
| `RabbitMQConfig` | Fanout Exchange(`ticker.exchange`), Queue(`engine.inbox`, durable) 선언. Publisher Confirms 설정. |

## 매칭 엔진에 시세 변경 이벤트 전달

collector는 tick을 발행할 뿐, 매칭을 하지 않는다. 대신 시세 변경 이벤트를 엔진에 발행한다.
따라서 collector는 engine의 내부 상태·결과를 알 필요가 없다. 발행만 보장하면 된다(at-least-once). 

## 설정 전략

`application.yml`에 모든 외부 설정을 관리하고, `@Value`로 주입한다. 세 거래소 모두 공개 API를 사용하므로 API 키가 필요 없다.

## 설계 결정

### RabbitMQ 시세 이벤트 발행

시세가 갱신될 때마다 RabbitMQ Fanout Exchange(`ticker.exchange`)로 시세 변경 이벤트를 발행한다. 트레이딩 서버(trypto-api)는 이 이벤트를 수신하여 WebSocket 브로드캐스트에 활용한다.

- **Publisher Confirms**: 브로커가 메시지를 수신했는지 확인한다
- **Fanout Exchange**: 모든 트레이딩 서버가 동일한 이벤트를 수신해야 하므로 Fanout을 사용한다. 큐 바인딩은 소비자(트레이딩 서버)가 담당한다

### engine.inbox 큐로 tick 발행

매칭 엔진은 별도 서비스(trypto-engine)로 분리되어 있으며, collector는 tick을 큐에 넣기만 한다.

- **큐 직접 발행**: `ticker.exchange`와 달리 매칭 엔진은 단일 소비자이므로 Exchange 대신 default exchange + 큐 이름 라우팅 키로 발행한다
- **durable queue**: 엔진 재기동 시 누락을 방지한다
- **`event_type` 헤더**: engine이 `OrderPlaced`/`OrderCanceled`/`TickReceived`를 헤더로 구분한다. collector는 `TickReceived`만 발행한다
- **at-least-once 보장**: engine이 이벤트 순서와 idempotency를 자체적으로 책임진다(WAL + 주문 상태 PENDING 체크)

### 초기화 전략

거래소 초기화는 리더 노드에서만 실행된다. `LeaderLifecycleListener`가 리더십 획득 이벤트를 받으면 `ExchangeInitializer.start()`를 호출한다. 리더십 상실 시 `stop()`으로 WebSocket 스레드를 정리한다. 각 거래소의 초기화(메타데이터 로딩 → 초기 시세 스냅샷 → WebSocket 연결)는 독립적으로 수행되어 하나가 실패해도 나머지에 영향이 없으며, 실패 시 지수 백오프로 무한 재시도한다.

### 리더 선출 (HA)

Redisson 분산 락 기반으로 단일 액티브 인스턴스를 보장한다. 다중 수집기 인스턴스 중 하나만 시세를 수집하여 engine으로의 중복 발행을 방지한다.

- **Watchdog 자동 연장**: `tryLock(0, -1)`로 leaseTime을 무한으로 설정하여 Watchdog이 30초마다 자동 연장한다
- **5초 간격 tick**: 리더 상실 감지 지연을 최소화한다
- **이벤트 기반 제어**: `LeadershipAcquiredEvent`/`LeadershipRevokedEvent`로 거래소 초기화를 제어한다
- **ApplicationReadyEvent 기반 시작**: 모든 빈과 `@EventListener` 등록 완료 후 스케줄러를 시작하여 이벤트 드롭을 방지한다

### Redis

Redis를 단일 인스턴스로 운영한다. Lettuce(데이터 접근)와 Redisson(분산 락) 모두 동일한 노드를 공유한다.

- **Lettuce**: Spring Data Redis의 `host` / `port` 설정으로 자동 연결
- **Redisson**: `useSingleServer()`로 `redis://host:port` 지정

### WebSocket 장애 시 REST 폴링 폴백

WebSocket 재연결이 지속적으로 실패하면 `RestPollingFallback`이 200ms 주기로 REST API를 폴링하여 시세를 수집한다. WebSocket 복구 시 자동 중지된다.

- **거래소별 폴링**: `ExchangeTickerPoller` 인터페이스를 통해 각 거래소 REST 클라이언트에 위임한다
- **NormalizableTicker**: REST 응답 DTO가 구현하는 인터페이스로, `code()`와 `toNormalized(displayName)`을 제공한다
- **인터럽트 가드**: 리더십 상실로 `shutdownNow()` 인터럽트가 걸릴 때 폴백이 시작되는 것을 차단한다

### 변동률(changeRate) 기준 차이

- 업비트/빗썸: `signed_change_rate`는 전일 종가 대비 변동률
- 바이낸스: `P`는 24시간 롤링 윈도우 대비 변동률

이 차이는 `NormalizedTicker`에서 별도 필드로 분리하지 않고, 소비자(백엔드)가 인지하도록 문서화한다.

### Redis TTL 30초

각 쓰기마다 TTL이 리셋된다. WebSocket이 끊겨 갱신이 중단되면 30초 후 키가 만료되어 소비자가 "시세 없음"을 인지할 수 있다.

### 마켓 메타데이터 Redis 저장

거래소별 상장 코인 목록(`MarketInfo`)을 Redis에 저장하여 백엔드(trypto-api)가 기동 시 조회하여 DB에 저장할 수 있도록 한다.

- **키 포맷:** `market-meta:{EXCHANGE}` (예: `market-meta:UPBIT`)
- **값:** `MarketInfo` 배열의 JSON (예: `[{"base":"BTC","quote":"KRW","pair":"BTC/KRW","displayName":"비트코인"}, ...]`)
- **TTL 없음:** 메타데이터는 수집기가 재기동할 때마다 덮어쓴다. 수집기가 죽어도 마지막 메타데이터가 유지되어 백엔드가 참조할 수 있다
- **저장 시점:** `ExchangeInitializer`가 REST API로 마켓 목록을 로딩한 직후, WebSocket 연결 전에 저장한다
