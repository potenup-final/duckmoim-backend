-- Chat 대역(V700~V799)의 첫 파일이다. 위키가 2026-09-10 에 두 자리 대역을 세 자리로
-- 다시 가르면서 Chat 에 V700~V799 를 주었고, 아직 아무도 쓰지 않았다.
--
-- CH-01 의 저장 자리다. 초대(CH-02) · 퇴장(CH-04) · 메시지(CH-07~) 는 각각의 티켓이다.
CREATE TABLE chat_room
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,

    -- 애그리게이트 밖은 ID 로만 참조한다 (도메인 3.2). FK 를 걸지 않는다.
    post_id    BIGINT      NOT NULL,

    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,

    PRIMARY KEY (id),
    -- I-16 채팅방은 모집글 하나에 하나다. 도메인 5장이 이중 방어를 유니크 제약으로 정했다.
    CONSTRAINT uq_chat_room_post_id UNIQUE (post_id)
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 상태 컬럼이 없다. 도메인 6장이 「ChatRoom 은 상태를 저장하지 않는다. 쓸 수 있는지
-- 여부는 모집글의 만남시각에서 계산한다」 고 정했다 (CH-08).
--
-- 방장 컬럼도 없다. 도메인 3.1 의 경계 안 목록이 「멤버 목록, 멤버별 마지막 읽은 지점,
-- 모집글 참조」 셋이고 방장이 없다 — 방장은 모집글이 아는 사실이다.

-- 멤버는 방 애그리게이트 안이다 (도메인 3.1). Message 만 방 밖이다.
CREATE TABLE chat_room_member
(
    id        BIGINT      NOT NULL AUTO_INCREMENT,

    room_id   BIGINT      NOT NULL,
    user_id   BIGINT      NOT NULL,

    joined_at DATETIME(6) NOT NULL,

    -- 나간 사람의 행을 지우지 않는다. I-19 의 이중 방어가 「퇴장 이력 조회」 이고
    -- (CH-02a 재초대 차단), 행을 지우면 스스로 나간 것과 초대받은 적 없는 것이
    -- 구분되지 않는다. 현재 멤버는 left_at IS NULL 이다 (CH-18).
    left_at   DATETIME(6) NULL,

    PRIMARY KEY (id),
    -- 같은 사람이 한 방에 두 행을 갖지 않는다. 재입장이 없으므로(CH-02a) 행이 늘 하나다.
    CONSTRAINT uq_chat_room_member UNIQUE (room_id, user_id)
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 「멤버별 마지막 읽은 지점」 컬럼이 아직 없다. 도메인 3.1 의 경계 안에는 있지만 타입이
-- CH-09 의 커서 정의에 달려 있고 그것이 아직 없다. CH-13(안 읽은 수) 티켓이 더한다.
--
-- created_at · updated_at 을 두지 않았다. joined_at 이 곧 생성 시각이고 갱신되는 값이
-- left_at 하나라 BaseEntity 의 두 컬럼이 같은 사실을 두 번 적게 된다.
