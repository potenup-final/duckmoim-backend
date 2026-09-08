-- Comment / Safety 대역(V30~V39). V30~V35 가 쓰였으므로 V36 이다.
-- 감사 로그도 백오피스라 V30 의 admin_accounts 와 같은 대역에 둔다.
--
-- 관리자가 개인정보·비공개 내용에 접근하거나 계정에 불이익을 준 행위를 남긴다
-- (도메인 1.1). 신고 처리는 여기 오지 않는다 — AD-03 의 이력은 report 가 진다.
CREATE TABLE audit_log
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,

    -- 행위자. 애그리게이트 밖은 ID 로만 참조한다 (도메인 3.2). FK 를 걸지 않는다.
    -- 카카오 회원번호가 아니라 회원번호다 — 회원번호는 응답에 나가고 카카오
    -- 회원번호는 어디에도 나가지 않는다.
    actor_user_id BIGINT      NOT NULL,

    -- SANCTION · RELEASE · SECRET_READ · BLIND · PURGE 다섯이다 (2026-09-05 확정).
    kind          VARCHAR(20) NOT NULL,

    -- 대상은 USER 아니면 COMMENT 다. 종류가 둘이라 FK 를 걸 수 없다 — report 와
    -- 같은 이유다 (V34).
    target_type   VARCHAR(20) NOT NULL,
    target_id     BIGINT      NOT NULL,

    -- 무엇을 왜 했는지의 한 줄. 신고 상세와 같은 500 으로 맞췄다.
    detail        VARCHAR(500) NULL,

    -- created_at · updated_at 을 두지 않는다. updated_at 은 「감사 로그는
    -- 수정·삭제되지 않는다」(I-13)와 정면으로 어긋나는 컬럼이고, 행위 시각이 곧
    -- 생성 시각이라 둘을 나눌 이유도 없다. 이름은 화면 계약의 응답 필드를 따랐다.
    at            DATETIME(6) NOT NULL,

    PRIMARY KEY (id),

    -- 목록은 최신순 (at, id) 커서다. 정렬 키를 쥔 티켓이 인덱스도 함께 붙인다 —
    -- V34 가 AD-02 의 인덱스를 미뤄 둔 것과 달리 여기는 조회가 같은 티켓에 있다.
    KEY idx_audit_log_listing (at DESC, id DESC)
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
