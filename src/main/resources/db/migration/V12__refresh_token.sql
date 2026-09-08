-- Identity / Auth 대역(V10~V19). V10 · V11 이 user 로 쓰였고 다음이 V12 다.
-- 고르기 전에 열려 있는 PR 도 확인했다 — 대역 표의 「현재」 열은 머지된 것만 세기 때문이다
-- (DB 마이그레이션 규칙 「대역은 사람이 아니라 영역에 붙는다」).
--
-- AU-02 「Refresh 는 서버 저장」의 자리다. AU-03 재사용 탐지와 AU-04 로그아웃이 이 표를 읽는다.
--
-- 테이블명이 auth_session 이 아닌 이유 — 애그리게이트 표가 「애그리게이트 AuthSession /
-- 루트 RefreshToken」으로 적었고 테이블은 루트를 따른다 (도메인 3.1). 그리고 실제로 담기는
-- 것은 Refresh 해시 한 줄뿐이다. Access 는 저장하지 않으므로 auth_session 은 담기지 않은
-- 것까지 있는 것처럼 들린다.
CREATE TABLE refresh_token
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,

    -- 애그리게이트 밖은 ID 로만 참조한다 (도메인 3.2). FK 를 걸지 않는다.
    -- 한 회원이 여러 기기에서 로그인하므로 유니크가 아니다. AU-03 의 「해당 유저 전체 폐기」가
    -- 이 컬럼으로 지운다.
    user_id    BIGINT      NOT NULL,

    -- 원문을 저장하지 않는다. DB 가 유출되면 그 자체로 14일짜리 세션 전부를 넘겨주는 셈이다.
    -- SHA-256 hex 라 길이가 정확히 64 로 고정되지만 CHAR 가 아니라 VARCHAR 다 —
    -- JPA 기본 매핑이 varchar 여서 CHAR 로 두면 ddl-auto=validate 가 기동을 막는다.
    -- 실측했다: "wrong column type encountered in column [token_hash]".
    token_hash VARCHAR(64) NOT NULL,

    -- Refresh 14일 (AU-02). 만료된 행을 지우는 배치는 1차 범위 밖이다 — 재발급 때 만료를
    -- 확인하므로 남아 있어도 통과하지 않는다.
    expires_at DATETIME(6) NOT NULL,

    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,

    PRIMARY KEY (id),

    -- 같은 토큰이 두 행으로 존재할 수 없다. AU-03 회전의 승자는 「아직 회전되지 않은 행」을
    -- 잡는 단일 UPDATE 의 영향 행 수가 가른다 — 그 표시 컬럼은 V13 이 넣는다.
    CONSTRAINT uk_refresh_token_token_hash UNIQUE (token_hash)
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 전체 폐기(AU-03)와 로그아웃(AU-04)이 user_id 로 지운다.
CREATE INDEX ix_refresh_token_user_id ON refresh_token (user_id);

-- AU-03 의 「해당 유저 전체 폐기」와 AU-04 의 「Access 잔여 TTL 차단」이 이 한 컬럼으로 같이
-- 처리된다. 필터가 토큰의 iat 와 이 값을 비교해 「그 시각 이전에 발급된 토큰」을 전부 막는다.
--
-- refresh_token 에 둘 수 없다 — 그 행을 전부 지운 뒤에는 비교할 행이 남지 않는다.
-- User 애그리게이트 경계(도메인 3.1)에는 없는 값이라 STAR-40 이 안 넣은 것이 맞고,
-- AuthSession 쪽 관심사이므로 이 티켓이 붙인다.
ALTER TABLE user
    ADD COLUMN tokens_invalidated_at DATETIME(6) NULL AFTER last_seen_at;
