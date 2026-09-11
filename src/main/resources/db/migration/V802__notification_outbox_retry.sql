-- Notification 대역(V800~V899). V801 이 알림함이므로 V802 다.
--
-- V800 이 「나머지 값은 NT-02 가 정한다」 로 미뤄 둔 컬럼들이다 (NT-02 · NT-03).
--
-- 재시도를 DB 컬럼으로 하는 이유 — Spring Retry 가 의존성에 없고, 인메모리 백오프는 스케줄러
-- 스레드를 붙잡고 앉아 기다린다. 마감 배치가 세운 「실패는 다음 주기가 대신 처리한다」 패턴과도
-- 어긋난다. 시각을 적어두면 워커가 그 시각이 지난 것만 집으면 된다.
ALTER TABLE notification_outbox
    -- 몇 번 시도했는지. 세 번이면 DLQ 로 옮긴다 (NT-03).
    ADD COLUMN attempts        INT         NOT NULL DEFAULT 0 AFTER status,

    -- 다음에 집어도 되는 시각. NULL 은 「지금 집어도 된다」 다.
    --
    -- 발행 시점에 NULL 로 들어오는 것이 의도다 (V800 의 INSERT 는 이 컬럼을 모른다). 처음
    -- 발행된 건을 즉시 보내려면 그래야 하고, 「한 번도 실패하지 않았다」 와 「지금 보낼 때다」 가
    -- 같은 뜻이라 값을 나눌 이유가 없다.
    ADD COLUMN next_attempt_at DATETIME(6) NULL AFTER attempts;

-- 미발행 조회 인덱스를 다시 만든다. V800 의 (status, id) 는 「PENDING 을 오래된 순으로」 까지만
-- 좁히는데, 재시도가 들어오면서 조회에 `next_attempt_at IS NULL OR next_attempt_at <= now`
-- 가 붙는다. 그 컬럼이 인덱스에 없으면 PENDING 전체를 훑고 나서 거른다 — 밀린 재시도가 쌓일수록
-- 훑는 양이 늘고, V800 주석이 경고한 잠금 범위도 그만큼 넓어진다.
--
-- 순서가 (status, next_attempt_at, id) 인 이유는 앞의 둘이 등호·범위 조건이고 마지막이 정렬
-- 키라서다. id 를 앞에 두면 범위 조건 뒤의 정렬이 인덱스로 풀리지 않는다.
DROP INDEX idx_notification_outbox_pending ON notification_outbox;

CREATE INDEX idx_notification_outbox_pending
    ON notification_outbox (status, next_attempt_at, id);
