-- Notification 대역(V800~V899). V800 이 아웃박스이므로 V801 이다.
--
-- 알림함이다 (NT-02). 워커가 아웃박스에서 집은 건을 여기에 만든다 — 도메인 3.1 이 이 애그리게이트의
-- 경계를 「수신자, 종류, 대상 참조, 읽음」 으로 정했다.
--
-- 조회(NT-08) · 읽음 처리(NT-09) · 안 읽은 수(NT-10) · 30일 만료(NT-11a) 는 각각의 티켓이다.
CREATE TABLE notification
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,

    -- 어느 발행에서 나온 알림인지. 애그리게이트 밖은 ID 로만 참조한다 (도메인 3.2).
    --
    -- **유니크 제약이 이 표의 멱등성이다.** 워커가 알림을 만든 뒤 아웃박스 상태를 바꾸기 전에
    -- 죽으면 다음 시도가 같은 행을 다시 집는데, 그때 알림이 두 번 생기면 안 된다. 인스턴스가
    -- 둘일 때 두 워커가 같은 행을 집는 경우도 같이 막힌다 (선점 자체는 NT-04 몫이다).
    outbox_id    BIGINT      NOT NULL,

    -- 알림을 받는 사람의 회원번호.
    recipient_id BIGINT      NOT NULL,

    -- common 의 NotificationKind 를 그대로 쓴다. 아웃박스와 알림함이 같은 종류 값을 쓰므로
    -- 두 벌을 두지 않는다 (V800 주석).
    kind         VARCHAR(20) NOT NULL,

    -- 대상 참조. 도메인 3.1 의 경계 안 목록에 있다 — 알림을 눌렀을 때 어디로 갈지가 이 둘이고,
    -- 워커가 Companion 에 물어볼 수 없어 아웃박스가 실어 온 값을 그대로 옮긴다.
    post_id      BIGINT      NOT NULL,
    comment_id   BIGINT      NOT NULL,

    -- 읽음. 회고 2026-09-10 이 「읽음 상태를 저장한다」 를 확정했다.
    --
    -- 상태 enum 을 두지 않은 이유 — 도메인 6장의 전이가 「안 읽음 → 읽음 → 파기」 이고 파기는
    -- 행 삭제(NT-11a)라 실제 상태가 둘뿐이다. NULL 이 곧 안 읽음이고, 시각까지 남으면 나중에
    -- 「언제 읽었나」 를 되돌릴 수 있다. enum 을 두면 이 컬럼과 두 벌이 된다.
    --
    -- 채우는 쪽은 NT-09 다. 이 티켓은 NULL 로 만들기만 한다.
    read_at      DATETIME(6) NULL,

    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT uq_notification_outbox_id UNIQUE (outbox_id)

    -- 알림함 목록의 인덱스를 두지 않았다. 그 질의는 「내 알림을 최신순 커서로」(NT-08) 이고
    -- 커서의 정렬 키가 아직 없다. V800 의 인덱스를 지금 넣은 것과 다른 자리다 — 그쪽은 워커의
    -- 잠금 읽기가 도메인 트랜잭션의 INSERT 를 막는 문제였고, 여기는 읽기 성능뿐이다.
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
