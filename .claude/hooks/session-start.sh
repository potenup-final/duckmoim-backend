#!/bin/sh
# 세션이 열릴 때 위키를 최신으로 맞추고 git 설정을 세운다.
#
# 왜 세션 시작인가 — 루프의 [1] 컨텍스트 확보가 docs/wiki 를 읽는다. 예전에는
# 이걸 Gradle 태스크로만 해서 `compileJava` 에 매달려 있었는데, [1] 은 구현보다
# 앞이라 그 시점에 빌드를 돌렸을 이유가 없다. 채워주는 장치가 필요한 순간보다
# 늦게 도는 구조였다.
#
# 왜 최신(--remote)인가 — 위키는 빌드하는 의존성이 아니라 읽는 문서다. 커밋에
# 핀을 고정할 이유가 없다. 고정해두면 누군가 핀을 올려야 최신이 되고, 아무도 안
# 올리면 팀원은 낡은 문서를 최신이라 믿고 읽는다. 실제로 그 상태였다.
#
# sh + git 만 쓴다. node 나 jq 가 없는 기기에서도 돌아야 한다 — 훅이 실행되지
# 않으면 위키가 조용히 낡은 채로 남고, 그게 지금 고치려는 문제다.

cat >/dev/null 2>&1   # stdin 을 비워 호출한 쪽이 EPIPE 를 보지 않게 한다

cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0
[ -e .git ] || exit 0

# 커밋 제목에 Jira 키를 붙이는 훅. 빌드를 한 번도 돌리지 않은 트리에는 없다.
git config core.hooksPath .githooks >/dev/null 2>&1

# .gitmodules 가 없는 브랜치 (하네스 머지 전에 갈라진 브랜치) 는 여기서 끝.
# 서브모듈을 선언하지 않은 트리에 위키를 만들어줄 방법은 없다 — 리베이스뿐이다.
[ -f .gitmodules ] || exit 0

# 포인터 변화를 git 이 안 보게 한다. 이게 없으면 최신으로 당길 때마다 status 가
# `M docs/wiki` 로 더러워지고, 팀원이 "이건 커밋해야 하나" 를 매번 판단하게 된다.
git config submodule.docs/wiki.ignore all >/dev/null 2>&1

# git pull 이 서브모듈에 손대지 못하게 한다.
#
# submodule.recurse=true 면 pull 이 위키를 **기록된 핀**으로 체크아웃한다. 그런데
# 우리는 핀을 관리하지 않기로 했으므로 핀은 아무도 안 챙기는 옛 값이다. 즉 pull 이
# 도와주려 하면 위키가 뒤로 간다 — 실측했다: a25d114(최신) → 46651e7(6시간 낡음).
# submodule.docs/wiki.ignore 는 status·diff 표시만 바꾸므로 이걸 막지 못한다.
#
# unset 이 아니라 false 로 명시한다. 전역 설정(~/.gitconfig)에 true 가 있으면
# 로컬을 지우는 것으로는 덮이지 않는다.
#
# 새 클론에는 이 설정이 없어서 pull 이 원래 서브모듈을 건드리지 않는다. 문제는
# STAR-22 시절 Gradle 태스크가 true 를 심어둔 기존 클론뿐이고, 이 줄이 그것을
# 되돌린다.
git config submodule.recurse false >/dev/null 2>&1

git submodule update --init --remote docs/wiki >/dev/null 2>&1 && exit 0

# 여기부터는 실패 경로. 조용히 넘기지 않는다 — 위키를 못 읽는데 못 읽는 줄
# 모르는 상태가 가장 나쁘다. 사람이 안 읽는 로그가 아니라 에이전트 컨텍스트로
# 들여보낸다.
if [ -f docs/wiki/README.md ]; then
	msg='위키(docs/wiki)를 최신으로 맞추지 못했습니다. 이미 받아둔 사본은 있으나 낡았을 수 있습니다. 요구사항·불변식·설계 결정을 인용할 때 최신이 아닐 수 있음을 밝히세요. 복구: git submodule update --init --remote docs/wiki'
	short='위키를 최신으로 맞추지 못했습니다 (낡은 사본으로 진행)'
else
	msg='위키(docs/wiki)가 비어 있고 가져오지도 못했습니다. 요구사항 검증 기준·불변식·설계 결정을 읽을 수 없는 상태입니다. 이 상태로 추측해서 구현하지 마세요. 위키는 PRIVATE 이므로 potenup-final/duckmoim-wiki 접근 권한이 필요합니다. 사람에게 권한을 요청하거나, 필요한 스펙을 직접 물어보세요. 복구: git submodule update --init --remote docs/wiki'
	short='위키(docs/wiki)를 읽을 수 없습니다 — 접근 권한을 확인하세요'
fi

printf '{"systemMessage":"%s","hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"%s"}}\n' \
	"$short" "$msg"
