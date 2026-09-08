-- Comment / Safety 대역(V30~V39). V30~V33 이 쓰였으므로 V34 다.
--
-- 신고는 대상 종류만 다른 하나의 계약이다 (API 설계 2-6). 그래서 표도 하나이고
-- target_type 으로 갈린다 — USER · POST · COMMENT.
CREATE TABLE report
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,

    -- 애그리게이트 밖은 ID 로만 참조한다 (도메인 3.2). FK 를 걸지 않는다.
    -- 컨텍스트 맵도 Safety → Identity 를 [ID 참조] 로 정했다.
    reporter_id BIGINT       NOT NULL,

    -- 대상이 세 종류라 한 컬럼으로 가리킬 수 없다. FK 를 걸 수 없는 이유이기도 하다.
    target_type VARCHAR(20)  NOT NULL,
    target_id   BIGINT       NOT NULL,

    reason      VARCHAR(30)  NOT NULL,

    -- 선택 입력이다. 명세서가 「사유 카테고리 + 상세」 라고만 하고 필수 여부와 길이를
    -- 정하지 않아, 댓글 본문 · 모집글 본문과 같은 500 으로 맞췄다.
    detail      VARCHAR(500) NULL,

    -- PENDING · PROCESSING · RESOLVED (도메인 6장). 접수는 PENDING 으로만 들어오고
    -- 나머지 전이는 백오피스(AD-03)가 만든다.
    status      VARCHAR(20)  NOT NULL,

    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),

    -- SF-01 「동일 대상 중복 접수 차단」의 이중 방어다. 사전 조회만으로는 동시 요청
    -- 2건에서 뚫린다. 도메인 3.3 이 I-01 닉네임 유일성에 대해 정한 처리 방식을 그대로
    -- 따른다 — 「DB 유니크 제약. 위반을 409 로 변환」.
    --
    -- 신고자별이다. 다른 사람이 같은 대상을 신고하는 것은 중복이 아니다.
    CONSTRAINT uk_report_reporter_target UNIQUE (reporter_id, target_type, target_id)
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 백오피스 목록(AD-02)은 최신순 (created_at, id) 커서다 (API 설계 3장). 그 인덱스는
-- 정렬 키를 쥔 그 티켓이 붙인다. V32 가 댓글에서 같은 판단을 했다.
