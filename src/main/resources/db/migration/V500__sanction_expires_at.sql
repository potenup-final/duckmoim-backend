-- Safety 대역(V500~V599)의 첫 파일이다. 2026-09-10 에 대역을 다시 가르면서
-- 두 자리를 동결했고 (V34 · V36 · V37 · V38), 그 뒤 Safety 가 표를 건드리는 것이
-- 처음이다. 열린 PR 은 없었다.
--
-- 제재 중인 회원 목록 (AD-10). 활성 제재를 만료 임박순으로 훑는다.

-- 이 제재가 스스로 풀리는 시각.
--
-- V38 이 「만료를 저장하지 않는다」고 적어 두었다. 그 결정이 막은 것은 누군가
-- 갱신해야 하는 상태값이다 — 「푼 적 없는데 만료된」 행을 누가 언제 바꾸는가가
-- 새로 필요해지는 것. 이 열은 생성 시각에 kind · issued_at · until 로 정해지고
-- 다시 바뀌지 않는다. 배치는 여전히 없고, 「지금 유효한가」는 그대로 조회 시점에
-- now 와 비교해 판정한다.
--
-- 열을 둔 이유는 AD-10 이 좁힐 user_id 가 없기 때문이다. 회원 하나를 읽는 경로는
-- 후보 몇 건을 읽어 도메인이 걸렀지만 (Sanction#isActiveAt), 전 회원을 만료
-- 임박순으로 커서 페이징하려면 활성 판정과 정렬 키가 둘 다 SQL 이어야 한다.
-- 메모리에서 거르면 한 페이지가 요청한 크기보다 작아져 hasNext 와 nextCursor 가
-- 어긋난다.
--
--   SUSPENDED        until 그대로
--   WARNED           issued_at + 1년 (도메인 6장 제재 축)
--   AGE_HOLD BANNED  NULL — 스스로 풀리지 않는다
ALTER TABLE sanction
    ADD COLUMN expires_at DATETIME(6) NULL AFTER until;

-- 기존 행을 채운다. 규칙을 SQL 로 적는 것은 이 한 번뿐이고, 그 뒤로는 엔티티가
-- 적는다 (Sanction#of). 두 벌이 되지 않도록 isActiveAt 도 이 열을 보게 바꿨다.
UPDATE sanction
SET expires_at = CASE kind
                     WHEN 'SUSPENDED' THEN until
                     WHEN 'WARNED' THEN DATE_ADD(issued_at, INTERVAL 1 YEAR)
                     ELSE NULL
    END;

-- 백오피스 목록(AD-10)이 쓰는 인덱스.
--
--   전체    WHERE released_at IS NULL AND (expires_at IS NULL OR expires_at > ?)
--           ORDER BY expires_at IS NULL, expires_at, id
--
-- released_at 을 선두에 둔다. 목록이 활성만 담으므로 이 조건은 늘 붙고,
-- 값이 NULL 인 쪽이 곧 후보라 선두에서 범위가 가장 많이 줄어든다.
--
-- id 를 뒤에 붙인 이유 — expires_at 은 중복이 생긴다. 같은 날 같은 기간으로
-- 정지된 둘이 페이지 경계에 걸리면 정렬이 불안정해져 누락 · 중복이 난다.
-- V33 · V37 이 같은 이유로 같은 모양을 썼다.
--
-- 「만료 없는 것이 뒤」는 인덱스로 풀리지 않는다. ORDER BY 의 첫 축이
-- expires_at IS NULL 이라 정렬이 컬럼 순서와 어긋나는데, 백오피스는 건수가 작고
-- 관리자가 넷이라 그 한 번의 정렬을 감수한다 (V37 이 같은 판단을 했다).
CREATE INDEX ix_sanction_listing ON sanction (released_at, expires_at, id);
