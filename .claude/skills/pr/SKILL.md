---
description: >
  Issue 생성 → Pull Request 생성 → Squash Merge까지 한 번에 처리한다.
  현재 브랜치의 변경 사항을 분석하여 GitHub Issue를 먼저 생성하고,
  Issue 번호가 포함된 PR을 생성한 뒤 main으로 자동 Squash Merge한다.
  추가 커밋은 절대 하지 않으며, git add/commit도 실행하지 않는다.
  TRIGGER: 사용자가 PR 생성을 요청하거나 /pr을 입력할 때.
---

# Issue → PR → Squash Merge → 로컬 정리

GitHub Issue를 먼저 생성하고, 현재 브랜치의 변경 사항으로 Pull Request를 생성한 뒤, main으로 자동 Squash Merge하고 로컬을 정리한다. **추가 커밋은 절대 하지 않는다.**

## 핵심 정책

- Issue를 먼저 만들고, PR 제목에 Issue 번호를 붙인다.
- Issue와 PR 모두 적절한 **label**과 **assignee**를 반드시 설정한다.
- Base 브랜치는 항상 `main`이다.
- 모든 내용은 한국어로 작성한다.
- Co-Authored-By 라인을 절대 포함하지 않는다.
- main과 충돌이 발생하면 rebase로 해결한다. 머지 커밋은 절대 남기지 않는다.

---

## 라벨 가이드

| 라벨 | 사용 기준 |
|------|----------|
| `enhancement` | 새 기능 구현 |
| `bug` | 버그 수정 |
| `documentation` | 문서 변경 |

## Assignee

- 항상 `kim-se-hee`를 assignee로 설정한다.

---

## 워크플로우

### 1. Git 상태 확인

```bash
git status
git branch --show-current
git log --oneline -5
git rev-list --count HEAD ^main 2>/dev/null || echo "0"
git diff main
```

### 2. 브랜치 규칙

- 브랜치명은 `feature/*` 형식을 따른다. Issue 번호를 포함하지 않는다.
- 예시: `feature/collector-common`, `feature/collector-exchanges`, `feature/collector-monitoring`
- 현재 `main`에 있으면 PR 생성 전에 `feature/*` 브랜치를 생성한다.

### 3. 변경 사항 분석 후 Issue 생성

**분석 대상:**
- `git diff main` 출력
- 변경된 파일 목록과 내용
- 현재 브랜치의 커밋 메시지들

**Issue 생성:**
```bash
gh issue create --title "Issue 제목" --body "Issue 내용" --label "<라벨>" --assignee "kim-se-hee"
```

생성된 Issue 번호를 PR 제목에 사용한다.

### 4. main 동기화 (Rebase)

```bash
git fetch origin main
git rebase origin/main
```

충돌이 발생하면:
1. 충돌 파일을 열어 양쪽의 변경 사항을 모두 반영한다
2. 해결 후 `git add <충돌-파일>` → `git rebase --continue`
3. 모든 충돌이 해결될 때까지 반복한다

### 5. 리모트 푸시

```bash
git push origin <현재-브랜치명>
```

### 6. PR 제목 컨벤션

```
[#ISSUE_NUMBER] 간결한 PR 설명
```

커밋 타입 접두사는 PR 제목에 사용하지 않는다.

예시:
```
[#3] 업비트/빗썸/바이낸스 시세 수집 구현
[#5] WebSocket 재연결 로직 개선
```

### 7. PR 본문

```markdown
## Summary

[1~3줄 요약]

- **변경 A** — 설명
- **변경 B** — 설명

---

## 주요 변경 사항

### 패키지별 변경

[각 패키지(client, redis, rabbitmq 등)의 변경 내용]

### 인프라 및 설정

[application.yml, docker-compose.yml, build.gradle 등 변경. 해당 없으면 생략.]

---

## 설계 결정

| 결정 | 이유 |
|------|------|
| [설계 선택] | [왜 이렇게 했는지] |

---

## 미반영 사항

[해당 없으면 생략.]

Closes #N
```

**PR 생성:**
```bash
gh pr create --title "[#N] PR 제목" --body "$(cat <<'EOF'
(위 레이아웃에 맞춘 본문)
EOF
)" --label "<라벨>" --assignee "kim-se-hee"
```

### 8. Squash Merge

PR 생성 후 즉시 main으로 Squash Merge한다.

**필수 규칙:**
- `--subject`와 `--body ""`를 **반드시 한 명령에** 함께 넘긴다.
- Squash 커밋 제목에 PR 번호를 포함한다.

```bash
gh pr merge --squash --subject "[#N] PR 제목 (#PR_NUMBER)" --body ""
```

### 9. 로컬 정리

```bash
git checkout main
git pull origin main
git branch -D <머지된-브랜치명>
```
