-- 모집글 목록 조회(PO-08)가 쓰는 인덱스.
--
-- V20 은 (status, meet_at) 을 두었다. 정렬 키를 쥔 티켓이 아직 오지 않아 커서 구성을
-- 모르는 상태에서 붙인 것이고, 둘이 어긋난 채로는 페이지 경계에서 누락이 생긴다 —
-- 커서는 (meet_at, id) 이고 meet_at 은 중복이 생긴다 (API 설계 3장).
--
-- 조회가 두 모양이다. status 는 선택 파라미터이고 생략하면 전체다 (API 설계 2-4).
--
--   모집중  WHERE status = 'OPEN' AND (meet_at, id) > (?, ?)
--           ORDER BY meet_at, id
--   전체    WHERE (meet_at, id) > (?, ?)
--           ORDER BY meet_at, id
--
-- 전자는 status 가 선두여야 탐색과 정렬이 함께 끝나고, 후자는 status 가 선두면
-- 인덱스를 아예 못 쓴다. 그래서 둘을 따로 둔다.
--
-- id 를 뒤에 붙인 이유 — meet_at 은 중복이 생긴다. 같은 시각에 만나는 글이 페이지
-- 경계에 걸리면 정렬이 불안정해져 누락·중복이 난다 (PO-08 의 검증 기준). V4 가 행사
-- 목록에서, V33 이 댓글 목록에서 같은 이유로 id 를 꼬리에 붙였다.
CREATE INDEX ix_companion_post_status_meet_at_id ON companion_post (status, meet_at, id);
CREATE INDEX ix_companion_post_meet_at_id ON companion_post (meet_at, id);

-- 위 첫 인덱스가 같은 선두 컬럼으로 더 길어 이쪽은 하는 일이 없다. 남겨두면 쓰기마다
-- 갱신 비용만 든다.
DROP INDEX ix_companion_post_status_meet_at ON companion_post;
