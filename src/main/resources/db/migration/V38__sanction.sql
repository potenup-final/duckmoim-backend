-- Comment / Safety 대역(V30~V39). V30~V37 이 쓰였으므로 V38 이다.
-- (V37 은 STAR-78 · PR #86 에서 리뷰 중이라 develop 에도 디렉터리에도 안 보인다.)
--
-- 이 표로 대역에 V39 하나만 남는다. 규칙 문서에 「차면 늘리지 않고 다시 가른다」를
-- 적어 두었다.
--
-- 제재는 신고 처리(AD-03)가 유저에게 내리는 조치다 (AD-04). 콘텐츠 축인 블라인드
-- (AD-07)와 달리 계정에 남는다.
CREATE TABLE sanction
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,

    -- 제재받는 회원. Safety → Identity 가 [ID 참조] 라 FK 를 걸지 않는다
    -- (컨텍스트 맵). report 가 같은 판단을 했다 (V34).
    user_id      BIGINT       NOT NULL,

    -- WARNED · AGE_HOLD · SUSPENDED · BANNED 넷이다. NONE 은 여기 오지 않는다 —
    -- 「제재가 없다」는 상태이지 걸 제재가 아니라서, 그 상태는 행이 없는 것으로
    -- 표현된다 (API 설계 2-7 이 해제를 DELETE 로 정한 것과 같은 이유다).
    kind         VARCHAR(20)  NOT NULL,

    -- 본인에게 그대로 보여주는 정보라 응답에 실린다 (AD-04 · AU-12). 그래서 필수다.
    -- 길이는 신고 상세와 같은 500 으로 맞췄다.
    reason       VARCHAR(500) NOT NULL,

    -- 발효 시각. 지금은 실행 시각과 같지만 이름을 나눠 둔다 — 화면 계약의
    -- 응답 필드가 issuedAt 이고, 예약 제재가 생기면 created_at 과 갈린다.
    issued_at    DATETIME(6)  NOT NULL,

    -- SUSPENDED 일 때만 값이 있다 (화면 계약 「제재 상태」). WARNED 는 조치일로부터
    -- 1년이라 계산으로 나오고, AGE_HOLD 는 본인이 답해야 풀리고, BANNED 는 해소가 없다.
    until        DATETIME(6)  NULL,

    -- 관리자가 푼 시각. NULL 이면 아직 안 풀린 것이다.
    --
    -- 행을 지우지 않고 이 컬럼으로 표현하는 이유 — 해제는 DELETE 요청이지만 그것은
    -- 「NONE 을 POST 하지 않는다」는 뜻이지 기록을 버린다는 뜻이 아니다. 같은 유저가
    -- 몇 번 제재받았는지는 백오피스가 판단에 쓰는 재료다. 감사 로그에도 RELEASE 가
    -- 남지만 그쪽은 관리자 행위의 기록이지 제재 이력이 아니다.
    released_at  DATETIME(6)  NULL,

    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),

    -- 활성 제재를 읽는 것이 이 표의 주 쿼리다 (I-14 쓰기 판정 · AU-12 안내).
    --
    --   WHERE user_id = ? AND released_at IS NULL
    --
    -- 만료는 조회 시 판정하므로 (until 과 1년 경과) 인덱스 조건에 넣지 않는다.
    -- 넣으면 「지금」이 조건에 들어가 인덱스를 못 탄다.
    KEY idx_sanction_active (user_id, released_at)
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
