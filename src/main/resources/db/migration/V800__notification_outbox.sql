-- Notification 대역(V800~V899)의 첫 파일이다. 위키 「버전 대역」 표가 2026-09-10 에 두 자리
-- 대역을 세 자리로 다시 가르면서 Notification 에 이 대역을 주었고, 아직 아무도 쓰지 않았다.
-- 아웃박스를 이 대역에 두는 것도 같은 표가 정했다.
--
-- NT-01 의 저장 자리다. 워커 발송(NT-02) · 재시도와 DLQ(NT-03) · 선점(NT-04) 은 각각의 티켓이다.
--
-- 코드는 common 에 둔다. 행을 넣는 쪽이 Companion(댓글)과 Chat(메시지) 둘이라 Notification
-- 안에 두면 그 둘이 Notification 을 참조해 의존이 거꾸로 흐른다 (도메인 2장·3.1 각주).
CREATE TABLE notification_outbox
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,

    -- 수신자의 회원번호. 애그리게이트 밖은 ID 로만 참조한다 (도메인 3.2). FK 를 걸지 않는다.
    --
    -- 「보낼 것」을 적어두는 표라 넣는 쪽이 수신자를 정한다. 워커가 나중에 계산하지 않는 이유는
    -- 의존 방향이다 — Notification 의 화살표가 Identity 하나뿐이라 Companion 에 「이 댓글의
    -- 모집글 방장이 누구인가」 를 물어볼 수 없다.
    recipient_id BIGINT      NOT NULL,

    -- POST_COMMENTED · COMMENT_REPLIED 둘이다. NT-06 이 정한 종류는 셋인데 셋째(채팅방 새
    -- 메시지)는 넣는 쪽인 Message 가 아직 없어, 쓰는 코드가 생기는 티켓에서 더한다.
    kind         VARCHAR(20) NOT NULL,

    -- 알림에 필요한 값은 행이 전부 들고 있어야 한다. 워커가 Companion 에 물어볼 수 없어서
    -- (위 recipient_id 주석) 나중에 채울 수 있는 값이 없다.
    --
    -- 채팅 알림이 들어오면 이 둘은 해당하지 않는다. 그 티켓이 자기 컬럼을 더하면서 이 둘을
    -- NULL 허용으로 바꾼다 — V700 이 「멤버별 마지막 읽은 지점」 을 CH-13 에 미룬 것과 같다.
    post_id      BIGINT      NOT NULL,
    comment_id   BIGINT      NOT NULL,

    -- 지금은 PENDING 하나다. 발행이 INSERT 로 끝나고 상태를 바꾸는 주체가 워커라, 전이와
    -- 나머지 값은 NT-02 가 정한다.
    status       VARCHAR(20) NOT NULL,

    -- BaseEntity 를 상속해 얻는다. audit_log 가 updated_at 을 버린 것은 「감사 로그는
    -- 수정·삭제되지 않는다」(I-13) 때문인데, 아웃박스는 워커가 상태를 바꾸므로 반대 결론이다.
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,

    PRIMARY KEY (id)

    -- 인덱스를 두지 않았다. 이 표를 읽는 유일한 질의가 「미발행 건을 집는다」(NT-02) 이고 그
    -- 정렬·선점 방식이 아직 없다. 정렬 키를 쥔 티켓이 인덱스도 함께 붙인다 — V34 가 AD-02 의
    -- 인덱스를 그 티켓에 미룬 것과 같다.
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
