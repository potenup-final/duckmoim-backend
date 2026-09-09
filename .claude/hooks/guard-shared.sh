#!/bin/sh
# PreToolUse(Bash) 가드 — permissions.deny 가 못 보는 문을 닫는다.
#
# permissions.deny 의 Edit(...) 규칙은 Edit 도구만 본다. Bash 는 다른 도구라
# 안 걸린다. 아래는 그 우회로다.
#
#   sed -i '' 's/x/y/' .githooks/prepare-commit-msg
#   cat > .github/scripts/jira.sh
#   git checkout main -- .github/workflows/ci-cd.yml
#
# 읽기는 통과시킨다. `cat .githooks/x` 는 되고 `cat > .githooks/x` 는 안 된다.
#
# 왜 sh + jq 인가 — 둘 다 macOS 기본 탑재다. node 로 쓰면 백엔드만 하는 팀원
# 기기에 없을 수 있고, 훅이 실행되지 않으면 조용히 통과한다. 가드가 없는데
# 있다고 믿는 상태가 가장 나쁘다.
#
# 이것도 완전하지 않다. 쓰기를 표현하는 방법은 무한하고 아래 패턴은 유한하다.
# 훅은 로컬이라 끌 수도 있다. 진짜 강제는 브랜치 보호와 CODEOWNERS 다.
#
# 그리고 이 가드는 **명령 텍스트**를 본다. 실제 쓰기 대상을 파싱하지 않는다.
# 그래서 무해한 명령도 막힌다 — 예를 들어 아래 둘은 보호 경로에 쓰지 않는데 막힌다.
#
#   grep -r "docs/wiki/" . > results.txt
#   echo "docs/wiki 고쳐야 함" >> TODO.md
#
# 의도한 방향이다. 무해한 명령을 막으면 표현을 바꿔 다시 실행하면 되고(10초),
# 실제 쓰기를 놓치면 팀 공용 파일이 조용히 망가진다. 명령을 파싱해 진짜 대상만
# 잡는 쪽은 훨씬 어렵고 구멍이 더 많이 생긴다. 대신 **막혔을 때 무엇을 하면
# 되는지**를 메시지가 알려줘야 한다. 아래 decide 의 문구가 그것이다.

payload=$(cat)
cmd=$(printf '%s' "$payload" | jq -r '.tool_input.command // ""' 2>/dev/null)

[ -n "$cmd" ] || exit 0

# 쓰기를 표현하는 것들. 리다이렉션 · 제자리 편집 · 파일 이동 · git 복원.
WRITE='(>|(^|[[:space:]])tee[[:space:]]|sed[^|;&]*-i|(^|[[:space:]])(mv|cp|rm|ln|truncate|chmod|chown|install|patch|dd)[[:space:]]|(^|[[:space:]])git[[:space:]]+(checkout|restore|clean|apply|rm|mv))'

# 팀 공용. 관리 주체가 다른 저장소이거나 자동화가 참조한다.
DENY='(\.githooks/|\.github/scripts/|\.github/workflows/jira-|\.github/PULL_REQUEST_TEMPLATE\.md|docs/wiki/)'

# 게이트를 낮춰 초록불을 만드는 우회로. 그리고 CI 를 깨뜨리는 것들.
ASK='(config/checkstyle/|src/test/java/com/duckmoim/architecture/|\.github/workflows/ci-cd\.yml|gradle/wrapper/|(^|[[:space:]])gradlew)'

decide() {
  printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"%s","permissionDecisionReason":"%s"}}\n' "$1" "$2"
  exit 0
}

# 쓰기 표현이 없으면 읽기다. 통과시킨다.
printf '%s' "$cmd" | grep -Eq "$WRITE" || exit 0

if printf '%s' "$cmd" | grep -Eq "$DENY"; then
  decide deny "팀 공용 파일이라 막았습니다. 프론트 저장소에 사본이 있거나 자동화가 참조합니다. 정말 고쳐야 하면 무엇이 왜 필요한지 적어 담당자에게 넘기세요. // 이 명령이 그 경로에 실제로 쓰지 않는다면(검색어·메모 등) 명령에서 경로 문자열을 빼고 다시 실행하세요 — 가드는 명령 텍스트를 보고 판단합니다. (docs/harness/backend.md · 손대면 안 되는 곳)"
fi

if printf '%s' "$cmd" | grep -Eq "$ASK"; then
  decide ask "게이트 규칙이나 빌드 설정을 바꾸려 합니다. 게이트가 막을 때 규칙을 고치는 것이 코드를 고치는 것보다 쉽지만, 그러면 게이트가 게이트가 아닙니다. // 이 명령이 그 경로에 실제로 쓰지 않는다면 경로 문자열을 빼고 다시 실행하세요."
fi

exit 0
