-- 모집글 마감 시각 (CH-19). Companion / Post 대역(V300~V399)의 첫 파일이다.
-- 위키가 2026-09-10 에 두 자리 대역을 세 자리로 다시 가르면서 Companion 에 V300~V399 를
-- 주었고, 아직 아무도 쓰지 않았다. V20~V23 은 동결된 옛 대역이다.
--
-- **Chat 티켓이 Companion 표를 건드리는 자리다.** 보관 기간의 기준이 「모집글 마감」이라
-- (명세 2-1 의 CH-19 · 도메인 4장 정책표 · 처리방침 제3조 · 제8조) 그 시각을 아는 표가
-- 여기뿐이다.

-- 마감된 시각. OPEN 인 글은 NULL 이다.
--
-- **updated_at 으로 대신하지 않는다.** CLOSED 가 종착이라 (도메인 6장 — 재개방이 없고
-- 수정도 막힌다) 지금은 두 값이 같지만, updated_at 은 「마지막으로 바뀐 시각」이지
-- 「마감된 시각」이 아니다. 나중에 이 표를 훑는 일괄 UPDATE 가 한 번 들어오면 그 순간
-- 모든 글의 90일이 조용히 밀리고, 밀린 것이 보이지 않는다 — 처리방침이 고지한 보유
-- 기간이라 틀리면 안 되는 값이다.
ALTER TABLE companion_post
    ADD COLUMN closed_at DATETIME(6) NULL;

-- 이미 마감된 글의 백필. 이 시점의 updated_at 은 마감 시각이 맞다 — 마감 뒤에 이 행을
-- 바꾸는 경로가 없다. 위 주석이 경계하는 것은 앞으로 생길 경로이고, 과거에 대해서는
-- 이 값이 우리가 가진 유일한 근거다.
--
-- **백필하지 않으면 배포 전에 마감된 방이 영영 파기되지 않는다.** closed_at 이 NULL 인
-- 채로 남아 조회 조건(closed_at < :cutoff)에 걸리지 않는다.
UPDATE companion_post
SET closed_at = updated_at
WHERE status = 'CLOSED'
  AND closed_at IS NULL;

-- 파기 배치의 대상 조회다 (CH-19).
--
--   FROM chat_room r JOIN companion_post p ON p.id = r.post_id
--   WHERE p.status = 'CLOSED' AND p.closed_at < ? AND r.purged_at IS NULL
--
-- **이 인덱스가 조인의 출발점을 정한다.** 파기 대상은 전체의 극소수인데, chat_room 쪽에서
-- 출발하면 방 전부를 훑고 나서 모집글로 거른다. 여기서 출발하면 (status, closed_at) 범위가
-- 먼저 좁히고 uq_chat_room_post_id 로 방을 PK 조인한다.
--
-- status 를 선두에 두는 것은 ix_companion_post_status_meet_at 과 같은 이유다 — 기본 필터가
-- 상태이고, closed_at 은 CLOSED 안에서만 의미가 있다 (OPEN 은 전부 NULL).
--
-- chat_room.purged_at 에는 인덱스를 두지 않는다. 위 순서에서 그 열은 PK 조인으로 이미 좁혀진
-- 행 하나를 거르는 조건이라 인덱스가 할 일이 없다.
CREATE INDEX ix_companion_post_purge ON companion_post (status, closed_at);
