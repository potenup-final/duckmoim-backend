-- Comment / Safety 대역(V30~V39).
-- 관리자 인가 판정용 화이트리스트 (API 설계 D-5).
-- 인증은 카카오가 하고, 이 표에 회원번호가 있는지로 인가를 판정한다.
-- User 에 권한 컬럼을 두지 않아 판정 근거가 아예 다른 테이블에 있고,
-- 일반 유저가 어떤 경로로도 관리자가 될 수 없다.
--
-- 등록은 DB 에서 직접 한다. 권한 부여 API 를 만들지 않는다 — 만드는 순간
-- 그 엔드포인트가 공격 표면이 된다 (D-5 의 지켜야 할 셋 중 첫째).
CREATE TABLE admin_accounts
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,

    kakao_user_id BIGINT      NOT NULL,
    granted_at    DATETIME(6) NOT NULL,

    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT uk_admin_accounts_kakao_user_id UNIQUE (kakao_user_id)
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
