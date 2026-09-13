-- Notification 대역(V800~V899). V805 가 만료 인덱스이므로 V806 이다.
--
-- 채팅 알림이 들어온다 (NT-06 의 셋째 종류 · NT-07). V800 이 이 자리를 문면으로 미뤄 두었다 —
-- 「채팅 알림이 들어오면 이 둘은 해당하지 않는다. 그 티켓이 자기 컬럼을 더하면서 이 둘을
-- NULL 허용으로 바꾼다」.
--
-- **세 표를 함께 고친다.** 같은 「보낼 것」이 아웃박스에서 알림함으로 옮겨지고 못 보낸 건은
-- DLQ 로 간다. 한 표만 고치면 옮기는 순간 NOT NULL 에 걸리고, DLQ 를 빼면 못 보낸 채팅
-- 알림이 무엇이었는지 영영 알 수 없다 (V803 주석).

-- 아웃박스 (NT-01).
ALTER TABLE notification_outbox
    -- 알림을 눌렀을 때 갈 방.
    ADD COLUMN room_id    BIGINT NULL AFTER comment_id,

    -- 어느 메시지가 알림을 만들었는지.
    --
    -- **메시지마다 한 건이라 이 값이 없으면 행끼리 구분되지 않는다** (NT-07 은 묶음을 넣지
    -- 않기로 했다). DLQ 로 간 뒤에는 이것이 「무엇을 못 보냈나」의 유일한 값이다.
    ADD COLUMN message_id BIGINT NULL AFTER room_id,

    -- 댓글 알림의 참조. 채팅 알림에는 해당하지 않아 이제 비어 있을 수 있다.
    MODIFY COLUMN post_id    BIGINT NULL,
    MODIFY COLUMN comment_id BIGINT NULL;

-- 알림함 (NT-02). 아웃박스가 실어 온 값을 그대로 옮기므로 같은 모양이어야 한다.
ALTER TABLE notification
    ADD COLUMN room_id    BIGINT NULL AFTER comment_id,
    ADD COLUMN message_id BIGINT NULL AFTER room_id,
    MODIFY COLUMN post_id    BIGINT NULL,
    MODIFY COLUMN comment_id BIGINT NULL;

-- DLQ (NT-03). 원본 아웃박스 행은 지워지므로 여기 없으면 복원할 수 없다.
ALTER TABLE notification_outbox_dlq
    ADD COLUMN room_id    BIGINT NULL AFTER comment_id,
    ADD COLUMN message_id BIGINT NULL AFTER room_id,
    MODIFY COLUMN post_id    BIGINT NULL,
    MODIFY COLUMN comment_id BIGINT NULL;

-- **CHECK 제약으로 「종류마다 채워야 하는 칸」을 강제하지 않는다.**
--
-- 강제하려면 kind 값을 DDL 이 알아야 하고, 그러면 알림 종류가 하나 늘 때마다 세 표의 제약을
-- 함께 고쳐야 한다. NT-11 의 수신 설정과 NT-12~15 의 푸시가 아직 남아 있어 이 대역은 계속
-- 자란다 — 종류 추가의 비용을 마이그레이션 셋으로 만들 자리가 아니다.
--
-- 대신 넣는 쪽이 판정한다. NotificationOutbox 의 정적 팩터리가 종류마다 따로 있고 각자
-- 자기 참조를 requireNonNull 로 받는다. V800 이 recipient_id 를 두고 「넣는 쪽이 정한다」로
-- 적은 것과 같은 자리다.
