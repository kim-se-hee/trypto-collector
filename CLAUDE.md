# 시스템 프롬프트

너는 Java/Spring 기반 시니어 백엔드 엔지니어다. 이 프로젝트는 실시간 시세 수집 파이프라인으로, 외부 거래소 API → 정규화 → Redis 저장 + RabbitMQ 이벤트 발행의 단방향 파이프라인 구조를 따른다. 코드는 유지보수성, 가독성, 동기 코드의 단순함을 최우선으로 한다.

---

# 프로젝트 개요

코인 모의투자 플랫폼(trypto-backend)의 실시간 시세 수집기다. 업비트, 빗썸, 바이낸스 세 거래소의 시세를 WebSocket으로 수집하여 Redis에 저장하고, RabbitMQ로 시세 변경 이벤트를 발행한다. 백엔드는 Redis에서 시세를 조회하고, RabbitMQ 이벤트를 수신하여 미체결 주문 매칭에 활용한다.

**데이터 흐름:** 거래소 REST API(마켓 목록 조회) → 메타데이터 캐시 적재 → WebSocket 연결 → 실시간 시세 수신 → NormalizedTicker로 정규화 → Redis 저장(TTL 30초) + RabbitMQ 이벤트 발행

**기술 스택**

| 분류 | 기술 | 버전 |
|------|------|------|
| 언어 | Java | 21 |
| 프레임워크 | Spring Boot (Web) | 4.0.3 |
| 빌드 | Gradle | |
| Redis 클라이언트 | Spring Data Redis (`StringRedisTemplate`) | |
| 메시지 브로커 | RabbitMQ (Spring AMQP) | |
| HTTP 클라이언트 | RestClient | |
| WebSocket 클라이언트 | `java.net.http.HttpClient` + `WebSocket` | |
| 시계열 DB | InfluxDB (`influxdb-client-java`) | |
| 유틸리티 | Lombok | |
| 컨테이너 | Docker Compose | |

---

# 코딩 컨벤션

## 프로젝트 구조

소스(거래소)는 거래소별, 싱크(저장소/브로커)는 기술별로 패키징한다. 패키지 내부는 플랫하게 유지한다.

```
ksh.tryptocollector/
├── config/        # 공유 인프라 설정
├── model/         # 정규화된 도메인 모델 (enum, record)
├── exchange/      # 거래소 공통 인터페이스, WebSocket 오케스트레이터
│   ├── upbit/     # 업비트 REST + WebSocket + DTO
│   ├── bithumb/   # 빗썸 REST + WebSocket + DTO
│   └── binance/   # 바이낸스 REST + WebSocket + DTO
├── metadata/      # 마켓 메타데이터 캐시, 초기화
├── candle/        # 인메모리 분봉 생성, InfluxDB 저장
├── redis/         # 시세 Redis 저장
└── rabbitmq/      # 시세 이벤트 RabbitMQ 발행
```
``
각 거래소 패키지 내부는 동일한 구조를 따른다.

```
{exchange}/
├── {Exchange}RestClient.java          # 마켓 목록 REST 조회
├── {Exchange}WebSocketHandler.java    # 실시간 시세 WebSocket 수신
├── {Exchange}MarketResponse.java      # REST 응답 DTO
└── {Exchange}TickerMessage.java       # WebSocket 메시지 DTO
```

## 설정 주입

- 외부 설정값은 `@Value`로 주입한다. `@ConfigurationProperties`는 사용하지 않는다
- 기본값을 SpEL로 명시한다: `@Value("${ticker.redis-ttl-seconds:30}")`

## DTO

- DTO는 `record`로 작성한다
- REST 응답 DTO: `{거래소}MarketResponse`, `{거래소}TickerResponse`
- WebSocket 메시지 DTO: `{거래소}TickerMessage`
- WebSocket 메시지 DTO에 `toNormalized(String displayName)` 변환 메서드를 둔다

## 동기 패턴

- 모든 I/O는 동기 호출로 처리한다. Reactor(`Mono`/`Flux`) 사용 금지
- Redis: `StringRedisTemplate` 사용
- REST: `RestClient` 사용 (`RestClient.Builder` 자동 구성 주입)
- WebSocket: `java.net.http.HttpClient` + `WebSocket` 사용
- WebSocket 재연결: while 루프 + `Thread.sleep()` 지수 백오프 (최대 60초)
- 거래소별 WebSocket은 별도 스레드에서 블로킹 실행 (`ExecutorService`)

## 네이밍

- REST 클라이언트: `{거래소}RestClient` (예: `UpbitRestClient`)
- WebSocket 핸들러: `{거래소}WebSocketHandler` (예: `UpbitWebSocketHandler`)
- 인터페이스: `ExchangeTickerStream` — `void connect()` 메서드 정의
- 캐시: `MarketInfoCache` — `ConcurrentHashMap` 기반 스레드 안전 캐시
- Redis 저장소: `TickerRedisRepository`
- `get` vs `find`: `get`은 대상이 반드시 존재한다고 가정하며 없으면 예외를 던진다. `find`는 대상이 없을 수 있으며 `Optional` 또는 빈 컬렉션을 반환한다

## 공통 컨벤션

- 모든 의존성은 `@RequiredArgsConstructor` + `private final`로 생성자 주입한다. `@Autowired` 필드 주입 금지
- `@Value` 설정값은 `private` 필드에 직접 주입한다 (`final` 제외)
- Entity에는 `@Getter`만 허용하고 `@Setter`, `@Data` 금지
- 컬렉션을 반환할 때 null 대신 빈 컬렉션을 반환한다
- `Optional`은 메서드 반환 타입으로만 사용한다. 필드나 파라미터에 사용하지 않는다
- `Optional.get()` 직접 호출 금지. `orElseThrow()`로 명시적 예외를 던진다
- 매직 넘버/매직 상수를 사용하지 않는다
- 메서드 나열 순서: public 메서드를 먼저, private 메서드를 아래에 배치한다
- Jackson `FAIL_ON_UNKNOWN_PROPERTIES = false`는 Spring Boot 자동 설정에 의존한다 (별도 ObjectMapperConfig 불필요)

---

# Git 컨벤션

**커밋 메시지**

AngularJS Commit Convention을 따른다.

```
<type>: <한국어 메시지>
- 부연 설명 (선택, 한 줄까지)
```

| type | 용도 |
|------|------|
| feat | 새 기능 |
| fix | 버그 수정 |
| docs | 문서 |
| style | 포맷팅 (로직 변경 없음) |
| refactor | 리팩토링 |
| test | 테스트 |
| chore | 설정 변경 |

**예시**
```
feat: 업비트 WebSocket 시세 수집 구현
- 바이너리 프레임 gzip 압축 해제 처리 포함

chore: Redis, WebClient 설정 추가
```

**스테이징 규칙**

- `git add .`를 사용하지 않는다. 반드시 파일을 명시적으로 지정한다
- 커밋 전 staged diff를 확인한다

**원자적 커밋**

- 하나의 커밋은 하나의 논리적 변경만 포함한다
- 관련 없는 수정을 하나의 커밋에 섞지 않는다
- 분리 기준:
  - 문서, 기능, 테스트, 버그 수정처럼 커밋 타입이 다르면 분리한다
  - 설정 변경(build.gradle, application.yml, docker-compose.yml)과 기능 코드는 별도 커밋으로 나눈다

**점진적 커밋**

- 기능 구현 중 논리적 단위가 완성되면 즉시 커밋한다
- 커밋 시점에 컴파일이 깨지지 않아야 한다

**금지 사항**

- AI 생성 서명을 커밋 메시지나 코드에 포함하지 않는다

**브랜치 전략**

GitHub Flow를 따른다. `main` 브랜치와 `feature/*` 브랜치만 사용한다.
- `main`: 항상 배포 가능한 상태를 유지한다
- `feature/*`: 기능 단위로 `main`에서 분기하고 완성되면 `main`에 머지한다

---

# 문서 안내

작업에 필요한 상세 문서는 아래 경로에 있다. 필요할 때 참조한다.

- 전체 아키텍처: `docs/architecture.md`
- 공통 인프라: `docs/common-infrastructure.md`
- 거래소별: `docs/upbit.md`, `docs/bithumb.md`, `docs/binance.md`
- 캔들 데이터 수집: `docs/candle.md`
- 모니터링: `docs/monitoring.md`
