-- Identity 대역(V10~V19). Comment · Safety · Admin 이 회원 없이는 한 줄도 못 나가서 먼저 세운다.
--   · 도메인 7.1 비밀 댓글 판정   → 요청자·방장·작성자·부모작성자 네 ID
--   · API 2-5 작성자 블록          → nickname · profile_image_url
--   · 도메인 7.2 lastSeen 구간     → last_seen_at
--   · API 설계 D-5 관리자 인가     → kakao_user_id
--
-- 컬럼은 도메인 모델링 3.1 의 User 애그리게이트 경계대로 처음부터 전부 넣었다.
-- 일부만 만들면 나머지를 ALTER 로 붙여야 하고, 그 ALTER 가 어느 대역에 들어가는지가 또 문제가 된다.
CREATE TABLE user
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,

    kakao_user_id     BIGINT       NOT NULL,

    -- SignupInfo · Profile (도메인 3.1). 가입 정보 입력 전에는 닉네임과 출생연도가 없다.
    nickname          VARCHAR(20)  NULL,
    birth_year        INT          NULL,
    intro             VARCHAR(100) NULL,
    profile_image_url VARCHAR(500) NULL,

    status            VARCHAR(30)  NOT NULL,

    -- 저장 전용이다. 어느 응답에도 그대로 나가지 않고 lastSeen 구간으로만 나간다 (도메인 7.2).
    last_seen_at      DATETIME(6)  NULL,
    withdrawn_at      DATETIME(6)  NULL,

    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT uk_user_kakao_user_id UNIQUE (kakao_user_id),
    -- I-01 닉네임 유일성의 이중 방어. 위반을 409 로 변환한다 (도메인 3.3).
    CONSTRAINT uk_user_nickname UNIQUE (nickname)
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
