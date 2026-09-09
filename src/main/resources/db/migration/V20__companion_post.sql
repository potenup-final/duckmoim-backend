-- Companion / Post 대역(V20~V29).
-- Comment 가 이 표에서 읽는 것은 셋이다 — 열린 글인지(CM-01), 방장이 누구인지(CM-10 · 도메인 7.1),
-- 글이 존재하는지. 나머지 컬럼은 도메인 3.1 애그리게이트 경계대로 미리 채웠다.
CREATE TABLE companion_post
(
    id              BIGINT         NOT NULL AUTO_INCREMENT,

    -- 애그리게이트 밖은 ID 로만 참조한다 (도메인 3.2). FK 를 걸지 않는다.
    host_id         BIGINT         NOT NULL,
    event_id        BIGINT         NULL,

    -- 행사명과 이미지는 조인이 아니라 스냅샷이다. 복제 대상은 이 둘뿐이고,
    -- ends_on 은 갱신 정책이 새로 필요해져 넣지 않기로 했다 (도메인 3.2 · 2026-09-05).
    event_title     VARCHAR(200)   NULL,
    event_image_url VARCHAR(500)   NULL,

    title           VARCHAR(40)    NOT NULL,
    content         VARCHAR(500)   NULL,

    meet_at         DATETIME(6)    NOT NULL,

    -- MeetPoint 는 장소명과 좌표를 모두 갖는다 (I-05).
    meet_place      VARCHAR(100)   NOT NULL,
    meet_lat        DECIMAL(10, 7) NOT NULL,
    meet_lng        DECIMAL(10, 7) NOT NULL,

    capacity        INT            NULL,

    status          VARCHAR(20)    NOT NULL,
    closed_reason   VARCHAR(30)    NULL,

    created_at      DATETIME(6)    NOT NULL,
    updated_at      DATETIME(6)    NOT NULL,

    PRIMARY KEY (id),
    -- I-03 정원은 선택 입력이고, 값이 있으면 2~6 이다.
    CONSTRAINT ck_companion_post_capacity CHECK (capacity IS NULL OR capacity BETWEEN 2 AND 6),
    CONSTRAINT ck_companion_post_lat CHECK (meet_lat BETWEEN -90 AND 90),
    CONSTRAINT ck_companion_post_lng CHECK (meet_lng BETWEEN -180 AND 180)
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- PO-08 목록은 만남시각 임박순이고 기본 필터가 status 다.
CREATE INDEX ix_companion_post_status_meet_at ON companion_post (status, meet_at);
