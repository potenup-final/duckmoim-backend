-- 댓글 목록 조회(CM-06 · CM-07)가 쓰는 인덱스.
--
-- V32 는 post_id 한 컬럼짜리를 두고 「복합 인덱스는 CM-06 · CM-07 티켓이 붙인다」 고
-- 미뤄 두었다. 정렬 키를 쥔 티켓이 정해야 맞기 때문이다.
--
-- 조회가 둘로 나뉜다. 커서가 루트 댓글만 세기 때문이다 (API 설계 3장) —
-- 대댓글까지 세면 페이지 경계에서 부모와 자식이 갈라진다.
--
--   루트    WHERE post_id = ? AND parent_id IS NULL AND (created_at, id) > (?, ?)
--           ORDER BY created_at, id
--   대댓글  WHERE post_id = ? AND parent_id IN (...)
--           ORDER BY parent_id, created_at, id
--
-- 둘 다 이 인덱스 하나로 탐색과 정렬이 함께 끝난다. 대댓글 쿼리에 post_id 를 함께
-- 거는 것이 그 이유다 — parent_id 만으로는 선두 컬럼을 못 써서 인덱스가 하나 더 필요해진다.
-- 대댓글은 어차피 부모와 같은 모집글에 속하므로 조건이 늘어도 결과가 달라지지 않는다.
--
-- id 를 뒤에 붙인 이유 — created_at 은 중복이 생긴다. 같은 시각에 달린 댓글이 페이지
-- 경계에 걸리면 정렬이 불안정해져 누락·중복이 난다 (CM-07 의 검증 기준). V4 가 행사
-- 목록에서 같은 이유로 (ends_on, id) 를 썼다.
CREATE INDEX ix_comment_post_parent_created_id ON comment (post_id, parent_id, created_at, id);

-- 위 인덱스의 선두 컬럼이 post_id 라 한 컬럼짜리는 하는 일이 없다. 남겨두면 쓰기마다
-- 갱신 비용만 든다.
DROP INDEX ix_comment_post_id ON comment;
