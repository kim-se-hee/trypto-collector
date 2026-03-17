---
description: >
  기능 문서 기반 코드 구현. docs/ 하위 문서를 읽고
  CLAUDE.md 컨벤션에 따라 ETL 파이프라인 코드를 구현한다.
  TRIGGER: 사용자가 기능 구현을 요청하거나 /implement를 입력할 때.
---

# 기능 문서 기반 코드 구현

기능 문서(`docs/{feature}.md`)를 읽고 코드를 구현한다. 에이전트 없이 메인 컨텍스트에서 직접 실행한다.

## 입력

`$ARGUMENTS` = 기능 문서 경로 (예: `docs/upbit.md`, `docs/common-infrastructure.md`)

---

## 페이즈 추적

각 Phase 시작 시 아래 명령어로 현재 페이즈를 기록한다. `{PHASE}` 부분만 해당 Phase 값으로 교체한다:

```bash
echo '{"phase":"{PHASE}","feature":"$ARGUMENTS"}' > "$HOME/.claude/implement-phase.json"
```

워크플로우 종료 시 반드시 페이즈 파일을 삭제한다:

```bash
rm -f "$HOME/.claude/implement-phase.json"
```

---

## Phase 1: 탐색

**페이즈 마커: `explore`**

이 단계에서는 반드시 읽기만 수행한다.

### 사전 읽기

구현 시작 전 아래 파일을 Read로 읽는다:

1. **대상 기능 문서** — `$ARGUMENTS`로 전달된 문서
2. **전체 아키텍처** — `docs/architecture.md` (데이터 흐름, 패키지 구조, 설계 결정)
3. **공통 인프라** — `docs/common-infrastructure.md` (공통 모델, Redis, RabbitMQ 명세)

거래소별 구현이면 해당 거래소 문서만 읽는다. 공통 인프라 작업이면 거래소 문서는 생략한다.

### 기존 코드 파악

기능 문서의 대상 패키지에 이미 구현된 코드가 있는지 확인한다:

- 같은 패키지의 기존 클래스 구조와 패턴 확인
- 다른 거래소의 동일 계층 구현이 있으면 참조 (예: Upbit WebSocket 핸들러 참고하여 Bithumb 구현)
- `common/model/`의 공통 모델 확인 (Exchange enum, NormalizedTicker, TickerEvent)

### 의존 관계 확인

구현할 기능이 의존하는 선행 구현이 완료되었는지 확인한다:

- **거래소 WebSocket 핸들러** → `common/model`, `metadata`, `redis`, `rabbitmq` 패키지가 필요
- **REST 클라이언트** → `common/config` (WebClient Bean)이 필요
- **Redis 저장소** → `common/model/NormalizedTicker`가 필요
- **RabbitMQ 발행** → `common/model/TickerEvent`가 필요

선행 구현이 없으면 사용자에게 알리고, 선행 구현부터 진행할지 확인한다.

---

## Phase 2: 구현

**페이즈 마커: `implement`**

CLAUDE.md의 코딩 컨벤션과 Git 컨벤션을 따라 구현한다.

### 구현 순서

해당 기능에 필요한 패키지만, **의존 방향(의존 대상 먼저)**을 따라 구현한다. 아래 의존 그래프를 참고하여 순서를 결정한다:

```
common/model  ←─ 모든 패키지가 의존
common/config ←─ client/rest, client/websocket
client/rest   ←─ metadata
metadata      ←─ client/websocket
redis         ←─ client/websocket
rabbitmq      ←─ client/websocket
client/websocket ←─ collector
collector     ←─ (최상위, 아무도 의존하지 않음)
```

**예시:**
- 새 거래소 추가 → `client/rest` DTO + 클라이언트 → `client/websocket` DTO + 핸들러 → `metadata` 등록
- Redis 저장 전략 변경 → `redis` 패키지만
- 새 출력 싱크 추가 → `common/model`(이벤트 DTO) → 새 패키지 → `client/websocket`에 연결

### 커밋 규칙

- 논리 단위마다 `./gradlew compileJava`로 검증 후 커밋한다
- 커밋 단위와 메시지는 CLAUDE.md의 Git 컨벤션을 따른다
- 설정 변경(`build.gradle`, `application.yml`, `docker-compose.yml`)과 기능 코드는 별도 커밋으로 나눈다

### 거래소별 구현 시 체크리스트

새 거래소를 추가할 때 반드시 확인한다:

- [ ] `Exchange` enum에 거래소 추가
- [ ] REST 클라이언트: `{거래소}RestClient` + `{거래소}MarketResponse` record
- [ ] WebSocket 핸들러: `{거래소}WebSocketHandler` implements `ExchangeTickerStream`
- [ ] WebSocket DTO: `{거래소}TickerMessage` record with `toNormalized(String displayName)`
- [ ] `MarketInfoCache`에 해당 거래소 데이터 적재 로직
- [ ] `ExchangeInitializer`에 초기화 + WebSocket 연결 트리거
- [ ] `application.yml`에 거래소별 설정 추가
- [ ] 재연결 로직: `retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(60)))`

### 컴파일 검증

구현 완료 후 전체 컴파일을 확인한다:

```bash
./gradlew compileJava
```

컴파일이 통과할 때까지 수정한다.

페이즈 추적을 종료한다:

```bash
rm -f "$HOME/.claude/implement-phase.json"
```

통과하면 Phase 3으로 넘어간다.

---

## Phase 3: 테스트

test-automator 서브에이전트에 위임한다.

프롬프트에 아래 정보를 포함한다:
- 기능 문서 경로
- Phase 2에서 생성/수정한 파일 목록

---

## Phase 4: 코드 리뷰

아래 4개의 리뷰어 서브에이전트를 **병렬**로 실행한다.

- architecture-reviewer
- code-quality-reviewer
- performance-reviewer
- concurrency-reviewer

리뷰 결과를 종합하여 사용자에게 보고한다.
