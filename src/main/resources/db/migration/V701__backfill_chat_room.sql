-- CH-01a 기존 모집글의 방.
--
-- 배포 시점에 이미 있는 모집글에도 방을 만든다. 명세가 그 까닭을 「조회 경로에 「방이 없는
-- 경우」 분기를 두지 않기 위해서」 라고 적었다 — 1차에 쓴 글은 CH-01 의 방아쇠(모집글 작성)를
-- 지난 적이 없어 이 파일이 없으면 영원히 방이 없다.
--
-- 만남시각이 지난 글의 방도 만든다. 읽기 전용으로 만드는 것은 CH-08 이 만남시각에서 계산하는
-- 일이고 (도메인 6장), 그래서 여기서 「오래된 글은 건너뛴다」 를 판정하지 않는다. 판정하면
-- 그 경계가 이 파일과 CH-08 두 곳에 생긴다.
--
-- 상태로도 거르지 않는다. CLOSED 인 글에도 방이 필요하다 — CH-08 이 「모집글이 CLOSED 여도
-- 이 구간 안이면 쓸 수 있다」 로 정했다.
--
-- 이 파일이 덮지 못하는 창이 하나 있다. blue-green 이 새 서버에서 이 마이그레이션을 돌린
-- 뒤에 트래픽을 옮기므로, 전환이 끝나기 전까지는 CH-01 의 리스너가 없는 구버전이 요청을
-- 받는다. 그 사이 작성된 모집글은 방이 없고 이 파일은 이미 적용돼 다시 돌지 않는다.
-- 그래서 조회 경로가 「방은 없을 수 없다」를 전제로 짜서는 안 된다 — 빈 경우를 어떻게
-- 답할지는 CH-06 의 계약이 정한다 (ChatRoomRepository javadoc).

-- 멱등이다. WHERE NOT EXISTS 가 이미 방이 있는 글을 건너뛴다. 유니크 제약(I-16)이 뒤를
-- 받치지만 그것만으로는 두 번째 실행이 예외로 끝나 마이그레이션이 실패한다 — 검증 기준이
-- 「두 번 돌려도 방이 하나」 이므로 조용히 아무 일도 없어야 한다.
INSERT INTO chat_room (post_id, created_at, updated_at)
SELECT p.id, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM companion_post p
WHERE NOT EXISTS (SELECT 1 FROM chat_room r WHERE r.post_id = p.id);

-- 방장이 유일한 멤버로 시작한다 (CH-01). 새 글이 지나는 경로와 같은 결과여야 한다 —
-- 백필로 생긴 방이 멤버 없이 남으면 방 상세(CH-06)가 멤버 목록에서 방장을 못 찾는다.
--
-- 방을 새로 넣은 건만 고르지 않고 방 전체를 훑는다. 첫 문장은 성공하고 이 문장에서 멈춘
-- 상태로 다시 돌 수 있어서다. 그때 「방금 넣은 방」 이라는 표식이 남아 있지 않다.
INSERT INTO chat_room_member (room_id, user_id, joined_at)
SELECT r.id, p.host_id, UTC_TIMESTAMP(6)
FROM chat_room r
         JOIN companion_post p ON p.id = r.post_id
WHERE NOT EXISTS (SELECT 1
                  FROM chat_room_member m
                  WHERE m.room_id = r.id
                    AND m.user_id = p.host_id);
