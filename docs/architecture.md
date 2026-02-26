# 아키텍처

## 개요

trypto-collector는 업비트, 빗썸, 바이낸스 세 거래소의 실시간 시세를 수집하여 Redis에 저장하는 Spring WebFlux 기반 수집기다. 백엔드(trypto-backend)는 Redis에서 시세를 조회하여 주문 체결, 수익률 계산 등에 활용한다.

헥사고날 아키텍처를 적용하지 않는다. 외부 API → 정규화 → Redis 저장의 단순한 단방향 파이프라인이므로, 기능별 패키지로 충분하다.

## 데이터 흐름

```
Application Startup
    │
    ▼
ExchangeInitializer (각 거래소 독립 로딩, 실패 격리)
    ├── Upbit REST → KRW- 필터 → MarketInfoCache 적재 → RealtimePriceCollector.connectUpbit()
    ├── Bithumb REST → KRW- 필터 → MarketInfoCache 적재 → RealtimePriceCollector.connectBithumb()
    └── Binance REST → USDT 필터 → MarketInfoCache 적재 + Redis 초기 스냅샷 저장 → RealtimePriceCollector.connectBinance()
            │
            ▼
RealtimePriceCollector (WebSocket 생명주기 관리)
    ├── UpbitWebSocketHandler.connect()
    │     → 구독 메시지 전송 → 바이너리 프레임 수신 → gzip 해제 → UpbitTickerMessage → toNormalized() → Redis 저장
    ├── BithumbWebSocketHandler.connect()
    │     → 구독 메시지 전송 → 텍스트 프레임 수신 → BithumbTickerMessage → toNormalized() → Redis 저장
    └── BinanceWebSocketHandler.connect()
          → 배열 배치 수신 → 캐시 기반 USDT 필터링 → BinanceTickerMessage → toNormalized() → Redis 저장 (concurrency 32)
            │
            ▼
Redis
    Key: ticker:{EXCHANGE}:{BASE}/{QUOTE}    예) ticker:UPBIT:BTC/KRW
    Value: JSON NormalizedTicker
    TTL: 30초
            │
            ▼
trypto-backend (Redis에서 시세 조회)
```

## 패키지 구조

```
src/main/java/ksh/tryptocollector/
├── TryptoCollectorApplication.java
├── common/
│   ├── config/
│   │   ├── RedisConfig.java                  ReactiveRedisTemplate 설정
│   │   └── WebClientConfig.java              WebClient 설정 (maxInMemorySize 5MB)
│   └── model/
│       ├── Exchange.java                     enum: UPBIT, BITHUMB, BINANCE
│       └── NormalizedTicker.java             정규화된 시세 record
├── metadata/
│   ├── model/
│   │   └── MarketInfo.java                마켓 메타데이터 record
│   ├── MarketInfoCache.java               ConcurrentHashMap 기반 인메모리 캐시
│   └── ExchangeInitializer.java           @PostConstruct 초기화, 메타데이터 로딩 오케스트레이터
├── client/
│   ├── rest/
│   │   ├── dto/
│   │   │   ├── UpbitMarketResponse.java      업비트 REST 응답 DTO
│   │   │   ├── BithumbMarketResponse.java    빗썸 REST 응답 DTO
│   │   │   └── BinanceTickerResponse.java    바이낸스 REST 응답 DTO
│   │   ├── UpbitRestClient.java              업비트 마켓 목록 조회
│   │   ├── BithumbRestClient.java            빗썸 마켓 목록 조회
│   │   └── BinanceRestClient.java            바이낸스 24hr 티커 조회
│   └── websocket/
│       ├── dto/
│       │   ├── UpbitTickerMessage.java        업비트 WebSocket 메시지 DTO
│       │   ├── BithumbTickerMessage.java      빗썸 WebSocket 메시지 DTO
│       │   └── BinanceTickerMessage.java      바이낸스 WebSocket 메시지 DTO
│       ├── ExchangeTickerStream.java      인터페이스: Mono<Void> connect()
│       ├── UpbitWebSocketHandler.java         업비트 WebSocket 핸들러
│       ├── BithumbWebSocketHandler.java       빗썸 WebSocket 핸들러
│       └── BinanceWebSocketHandler.java       바이낸스 WebSocket 핸들러
├── redis/
│   └── TickerRedisRepository.java             NormalizedTicker JSON 저장 + TTL
└── collector/
    └── RealtimePriceCollector.java            WebSocket 연결 오케스트레이터
```

## 컴포넌트 역할

| 컴포넌트 | 역할 |
|----------|------|
| `ExchangeInitializer` | 애플리케이션 시작 시 각 거래소 REST API를 호출하여 마켓 메타데이터를 로딩하고, 완료 후 WebSocket 연결을 트리거한다. 각 거래소는 독립적으로 로딩되어 하나가 실패해도 나머지에 영향이 없다. |
| `MarketInfoCache` | `ConcurrentHashMap` 기반 인메모리 캐시. 거래소별 심볼 코드 → `MarketInfo` 매핑을 저장한다. WebSocket 핸들러가 displayName 조회 및 바이낸스 USDT 필터링에 사용한다. |
| `{거래소}RestClient` | WebClient로 거래소 REST API를 호출하여 마켓 목록을 조회한다. KRW/USDT 마켓만 필터링한다. |
| `{거래소}WebSocketHandler` | 거래소 WebSocket에 연결하여 실시간 시세를 수신한다. 수신된 메시지를 `NormalizedTicker`로 변환하여 Redis에 저장한다. 연결 끊김 시 지수 백오프로 재연결한다. |
| `RealtimePriceCollector` | `ExchangeInitializer`에 의해 호출되어 각 거래소 WebSocket 연결을 시작하고 관리한다. |
| `TickerRedisRepository` | `NormalizedTicker`를 JSON으로 직렬화하여 Redis에 저장한다. TTL 30초로 설정하여 WebSocket이 끊기면 자동 만료된다. |

## 설정 전략

`application.yml`에 모든 외부 설정을 관리하고, `@Value`로 주입한다.

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

세 거래소 모두 공개 API를 사용하므로 API 키가 필요 없다.

## 설계 결정

### Reactive Redis

WebFlux 기반 프로젝트이므로 `ReactiveRedisTemplate`을 사용한다. 블로킹 `StringRedisTemplate`을 사용하면 `Mono.fromRunnable().subscribeOn(Schedulers.boundedElastic())`으로 감싸야 하는데, 이는 WebFlux 안티패턴이다.

### @PostConstruct 초기화

`ExchangeInitializer`는 `@PostConstruct`에서 각 거래소 로딩을 `subscribe()`로 비동기 실행한다. 리액티브 체인을 구독만 하고 바로 반환하므로 시작을 블로킹하지 않으며, 실패 시 무한 재시도(지수 백오프)로 복구한다.

### 변동률(changeRate) 기준 차이

- 업비트/빗썸: `signed_change_rate`는 전일 종가 대비 변동률
- 바이낸스: `P`는 24시간 롤링 윈도우 대비 변동률

이 차이는 `NormalizedTicker`에서 별도 필드로 분리하지 않고, 소비자(백엔드)가 인지하도록 문서화한다.

### Redis TTL 30초

각 쓰기마다 TTL이 리셋된다. WebSocket이 끊겨 갱신이 중단되면 30초 후 키가 만료되어 소비자가 "시세 없음"을 인지할 수 있다.
