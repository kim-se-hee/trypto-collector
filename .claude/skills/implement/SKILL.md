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

## Phase 1: 워크트리 · 브랜치 생성

`$ARGUMENTS`에서 파일명(확장자 제외)을 추출하여 브랜치와 워크트리를 만든다.

- 예: `docs/candle.md` → 브랜치 `feature/candle`, 워크트리 `candle`

```bash
# 파일명 추출 (예: docs/candle.md → candle)
FEATURE_NAME=$(basename "$ARGUMENTS" .md)
BRANCH_NAME="feature/${FEATURE_NAME}"
WORKTREE_PATH="../trypto-collector-${FEATURE_NAME}"

git branch "$BRANCH_NAME" main
git worktree add "$WORKTREE_PATH" "$BRANCH_NAME"
```

워크트리 생성 후 해당 워크트리 경로로 이동하여 이후 Phase를 진행한다.

---

## Phase 2: 탐색

이 단계에서는 반드시 읽기만 수행한다.

### 사전 읽기

구현 시작 전 아래 파일을 Read로 읽는다:

1. **대상 기능 문서** — `$ARGUMENTS`로 전달된 문서
2. **전체 아키텍처** — `docs/architecture.md` (데이터 흐름, 패키지 구조, 설계 결정)
3. **공통 인프라** — `docs/common-infrastructure.md` (공통 모델, Redis, RabbitMQ 명세)
4. 거래소별 구현이면 해당 거래소 문서만 읽는다. 공통 인프라 작업이면 거래소 문서는 생략한다.

### 의존 관계 확인

구현할 기능이 의존하는 선행 구현이 완료되었는지 확인한다:

- **거래소 WebSocket 핸들러** → `model`, `metadata`, `redis`, `rabbitmq` 패키지가 필요
- **REST 클라이언트** → `config` (WebClient Bean)이 필요
- **Redis 저장소** → `model/NormalizedTicker`가 필요
- **RabbitMQ 발행** → `model/TickerEvent`가 필요

선행 구현이 없으면 사용자에게 알리고, 선행 구현부터 진행할지 확인한다.

---

## Phase 3: 구현

CLAUDE.md의 코딩 컨벤션과 Git 컨벤션을 따라 구현한다.
해당 기능에 필요한 패키지만, **의존 방향(의존 대상 먼저)**을 따라 구현한다.

## Phase 4: 코드 리뷰

아래 4개의 리뷰어 서브에이전트를 **병렬**로 실행한다.

- architecture-reviewer
- code-quality-reviewer
- performance-reviewer
- concurrency-reviewer

리뷰 결과를 종합하여 사용자에게 보고한다.
