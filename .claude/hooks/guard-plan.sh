#!/bin/sh
# 계획을 이슈에 올리기 전에는 src/ 를 못 쓰게 한다.
#
# 왜 이걸 막는가 — 루프의 [2] 는 "계획을 이슈 본문에 올린다" 인데, CLAUDE.md 에
# 적어두는 것만으로는 급할 때 건너뛰어진다. 부탁이 아니라 설정이어야 한다.
#
# 무엇을 검사하는가 — **승인 여부가 아니라 게시 여부다.** 이슈 본문에 계획 절
# (`## 🎯 작업 내용`) 이 있는지만 본다. 사람의 마음 상태가 아니라 파일의 사실이라
# 표식 파일도 필요 없고 위조할 것도 없다.
#
# 자동 생성 문구의 부재로 판정하지 않는다. 사람이 본문을 아무렇게나 고쳐도
# 그 문구는 사라지므로, 있어야 할 것이 있는지를 보는 편이 정확하다.
#
# 왜 캐시하는가 — gh issue view 가 0.5초다. 편집마다 부르면 못 쓴다. 게시는
# 한 방향 전이라서(올린 계획이 자동 문구로 되돌아가지 않는다) 긍정 결과만
# 캐시한다. 부정은 매번 다시 본다. 캐시는 저장소 밖에 둬서 커밋에 섞이지 않는다.

payload=$(cat)
tool=$(printf '%s' "$payload" | jq -r '.tool_name // empty')

case "$tool" in
	Write|Edit|NotebookEdit)
		target=$(printf '%s' "$payload" | jq -r '.tool_input.file_path // empty')
		printf '%s' "$target" | grep -q '/src/\|^src/' || exit 0
		;;
	Bash)
		cmd=$(printf '%s' "$payload" | jq -r '.tool_input.command // empty')
		# 쓰기로 보이는 명령이면서 src/ 를 가리킬 때만 본다. 읽기는 통과시킨다.
		printf '%s' "$cmd" | grep -qE '(>|(^|[[:space:]])tee[[:space:]]|sed[^|;&]*-i|(^|[[:space:]])(mv|cp|rm|ln|truncate|patch|dd)[[:space:]])' || exit 0
		printf '%s' "$cmd" | grep -q 'src/' || exit 0
		;;
	*) exit 0 ;;
esac

cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0

branch=$(git branch --show-current 2>/dev/null)
key=$(printf '%s' "$branch" | sed -nE 's#.*/([A-Z]+-[0-9]+)$#\1#p')

# 티켓 브랜치가 아니면 판정 근거가 없다. 막지 않는다 — 근거 없이 막으면
# 사람이 훅을 꺼버리고, 그게 훅이 없는 것보다 나쁘다.
[ -n "$key" ] || exit 0

cache="${TMPDIR:-/tmp}/duckmoim-plan-$key"
[ -f "$cache" ] && exit 0

body=$(gh issue list --state all --search "\"[$key]\" in:title" \
	--json number --jq '.[0].number' 2>/dev/null \
	| xargs -I{} gh issue view {} --json body --jq .body 2>/dev/null)

# 이슈를 못 읽으면(오프라인·권한) 막지 않는다. 위와 같은 이유다.
[ -n "$body" ] || exit 0

if ! printf '%s' "$body" | grep -q '## .* 작업 내용'; then
	reason="루프 [2] 를 아직 안 했습니다. $key 이슈 본문에 계획 절(## 🎯 작업 내용)이 없습니다. 구현 전에 범위·계획·참고사항을 이슈에 올리세요 (gh issue edit). 계획 목록 한 줄이 커밋 하나가 됩니다."
	printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"%s"}}\n' "$reason"
	exit 0
fi

: > "$cache"
exit 0
