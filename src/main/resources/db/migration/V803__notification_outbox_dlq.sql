-- Notification 대역(V800~V899). V802 가 재시도 컬럼이므로 V803 이다.
--
-- 세 번 시도하고 못 보낸 건이 오는 자리다 (NT-03). 명세 문면이 「별도 표로 옮기고」라, 아웃박스에
-- 상태 하나를 더 두는 대신 표를 가른다 — 아웃박스는 워커가 10초마다 훑는 표이고 죽은 건이 그 안에
-- 쌓이면 훑는 양이 계속 는다.
CREATE TABLE notification_outbox_dlq
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,

    -- 옮겨 오기 전 아웃박스에서의 번호. 로그에 남은 outboxId 로 이 행을 찾을 수 있어야 한다.
    outbox_id    BIGINT      NOT NULL,

    -- 아웃박스 행이 들고 있던 「보낼 것」을 그대로 옮긴다. 원본 행은 지우므로 여기 없으면
    -- 무엇을 못 보냈는지 영영 알 수 없다.
    recipient_id BIGINT      NOT NULL,
    kind         VARCHAR(20) NOT NULL,
    post_id      BIGINT      NOT NULL,
    comment_id   BIGINT      NOT NULL,

    -- 몇 번 시도하고 포기했는지. 상한이 설정값이라 (max-attempts) 나중에 바뀌면 이 값으로
    -- 「그때는 몇 번이었나」를 알 수 있다.
    attempts     INT         NOT NULL,

    -- 언제 포기했는지. 아웃박스의 created_at 은 발행 시각이라 다른 값이다.
    failed_at    DATETIME(6) NOT NULL,

    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,

    PRIMARY KEY (id),

    -- 같은 발행이 두 번 옮겨지지 않는다. 워커 둘이 같은 건을 집을 수 있는 동안 (선점은 NT-04)
    -- 이관도 겹칠 수 있고, 그때 DLQ 에 같은 건이 두 줄이면 백오피스가 두 번 세게 된다.
    CONSTRAINT uq_notification_outbox_dlq_outbox_id UNIQUE (outbox_id)

    -- 실패 사유를 담지 않는다. 예외는 워커 로그에 스택 트레이스와 함께 남고 (NT-03 은 「별도
    -- 표로 옮기고 백오피스에 노출」까지다), 사유 문자열을 표에 넣으면 무엇을 담아도 되는 칸이
    -- 되어 개인정보가 흘러들 자리가 생긴다.
    --
    -- 백오피스 조회 API 도 아직 없다. 엔드포인트·응답 필드가 API 설계에 한 줄도 없어 지어내면
    -- 프론트와 갈라진다 — 그 계약이 정해지는 티켓이 만든다.
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
