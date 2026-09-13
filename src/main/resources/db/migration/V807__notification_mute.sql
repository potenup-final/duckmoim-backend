-- Notification 대역(V800~V899). V806 이 채팅 참조이므로 V807 이다.
--
-- 종류별 수신 설정이다 (NT-11). 명세 문면이 「종류마다 끌 수 있다. **전체 끄기 하나만 두지
-- 않는다**」이고, 결정 안건 2-5 가 그 이유를 적어 두었다 — 채팅 알림이 알림함을 채울 때
-- 전체 끄기밖에 없으면 사용자가 댓글 알림까지 함께 끄고, 그러면 알림을 넣은 이유가 사라진다.
CREATE TABLE notification_mute
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,

    -- 설정을 가진 사람의 회원번호. 애그리게이트 밖은 ID 로만 참조한다 (도메인 3.2). FK 는 걸지
    -- 않는다.
    user_id    BIGINT      NOT NULL,

    -- **끈 종류만 적는다.** 행이 있으면 끈 것이고 없으면 받는 것이다.
    --
    -- enabled 불리언을 두는 쪽을 고르지 않았다. 기본값이 「셋 다 켜짐」이라 true 행이 전부
    -- 군더더기가 되고, 티켓이 정한 「행이 없는 것이 곧 전부 받는다 — 가입 시점에 행을 만들지
    -- 않아도 된다」와도 어긋난다.
    --
    -- common 의 NotificationKind 를 그대로 쓴다. 아웃박스·알림함과 같은 값이라 두 벌을 두지
    -- 않는다 (V800 주석).
    kind       VARCHAR(20) NOT NULL,

    -- BaseEntity 를 상속해 얻는다. 이 행은 만들어지거나 지워질 뿐 고쳐지지 않아 updated_at 이
    -- 늘 created_at 과 같다. 그래도 컬럼 하나 때문에 상위 클래스를 가르지는 않는다 —
    -- notification_outbox_dlq 가 같은 성격이면서 같은 선택을 했다.
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,

    PRIMARY KEY (id),

    -- **같은 종류를 두 번 꺼도 한 건이다.** 토글은 몇 번을 눌러도 결과가 같아야 하고, 두 줄이
    -- 생기면 켤 때 하나만 지워져 꺼진 채로 남는다.
    --
    -- 이 인덱스가 읽기 두 갈래를 다 받는다 — 설정 화면의 「내 것 전부」(user_id 로 시작)와
    -- 발행 직전의 「이 종류를 끈 사람들」(user_id IN (...) AND kind = ?)이다. 발행 쪽을 위해
    -- (kind, user_id) 를 따로 두지 않는 것은, 수신자가 이미 손에 있어 IN 목록이 방 인원만큼
    -- (2~6명) 짧기 때문이다.
    CONSTRAINT uq_notification_mute UNIQUE (user_id, kind)
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
