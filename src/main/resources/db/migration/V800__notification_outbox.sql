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

    PRIMARY KEY (id),

    -- 「미발행 건을 오래된 순으로」 가 아웃박스의 정의라 (NT-02) 이 두 컬럼은 워커의 선점
    -- 방식이 무엇으로 정해지든 바뀌지 않는다. V34 가 AD-02 의 인덱스를 그 티켓에 미룬 것과
    -- 다른 자리다 — 그쪽은 읽기 전용 목록이고, 여기는 워커의 조회와 도메인 트랜잭션의
    -- INSERT 가 같은 표에서 만난다.
    --
    -- **성능이 아니라 I-25 때문에 지금 넣는다.** 인덱스가 없으면 워커의
    -- `WHERE status = 'PENDING' ... FOR UPDATE` 가 풀스캔이 되고, REPEATABLE READ 에서
    -- 풀스캔 잠금 읽기는 훑은 행과 그 사이 갭까지 잠근다. 그러면 댓글 작성의 아웃박스
    -- INSERT 가 워커를 기다리고, 알림 인프라의 느려짐이 댓글 작성 응답으로 샌다 — I-25 가
    -- 막으려는 것이 정확히 그 경로다.
    INDEX idx_notification_outbox_pending (status, id)

    -- NT-04 에 넘기는 것 — **이 인덱스만으로는 끝나지 않는다.** 락 범위가 표 전체에서 맞는
    -- 범위로 줄어들 뿐이고, 대기 건이 적어 스캔이 범위 끝까지 가면 그 끝의 갭이 잠긴다.
    -- 하필 그 자리에 새 PENDING 행이 들어가면 INSERT 가 여전히 기다린다. 선점을
    -- `SKIP LOCKED`(MySQL 8.4 라 쓸 수 있다) 나 `UPDATE` 로 하거나, 워커 트랜잭션만
    -- READ COMMITTED 로 낮춰 갭 락을 없애는 것 중 하나를 골라야 한다.
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
