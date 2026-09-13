-- Notification 대역(V800~V899). V807 이 수신 설정이므로 V808 이다.
--
-- 웹 푸시 구독이다 (NT-12). 브라우저가 준 「이 기기로 보내는 주소」와 「본문을 암호화할 키」를
-- 저장한다. 한 유저가 기기 수만큼 가질 수 있다 — 명세의 검증 기준이 「기기 둘에서 등록하면
-- 둘 다 받는다」다.
CREATE TABLE push_subscription
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,

    -- 구독의 주인. 애그리게이트 밖은 ID 로만 참조한다 (도메인 3.2). FK 는 걸지 않는다.
    user_id       BIGINT       NOT NULL,

    -- 푸시 서비스가 발급한 주소. 이것이 곧 기기다.
    --
    -- 1024 인 이유 — 서비스마다 길이가 다르다. FCM 은 230자 안팎이지만 Apple 은 그보다 훨씬
    -- 길고, 규격이 상한을 정하지 않아 넉넉히 잡는다. 짧게 잡으면 특정 브라우저에서만 등록이
    -- 실패하는데, 그 실패는 그 브라우저를 쓰는 사람에게만 보인다.
    endpoint      VARCHAR(1024) NOT NULL,

    -- 위 주소의 SHA-256(hex). **유니크 제약을 걸기 위한 컬럼이다.**
    --
    -- endpoint 에 직접 걸 수 없다. InnoDB 의 인덱스 키 상한이 3072바이트이고 utf8mb4 는
    -- 문자당 4바이트로 계산해서, VARCHAR(1024) 는 4096바이트라 인덱스에 안 들어간다.
    -- 앞부분만 자르는 접두어 인덱스는 답이 아니다 — 같은 서비스의 주소는 앞이 전부 같아서
    -- 서로 다른 기기가 같은 값으로 부딪힐 수 있다.
    --
    -- 생성 컬럼(SHA2)이 아니라 애플리케이션이 채운다. 생성 컬럼으로 두면 조회 조건이
    -- SHA2() 를 써야 해서 JPQL 로 표현되지 않고 네이티브 질의가 는다.
    --
    -- CHAR 가 아니라 VARCHAR 인 것은 JPA 쪽 사정이다. 길이가 고정이라 CHAR 가 더 맞지만,
    -- 엔티티의 length 속성은 varchar 로 매핑돼 스키마 검증이 타입 불일치로 기동을 막는다.
    -- 엔티티에 columnDefinition 을 적어 맞출 수도 있으나 그러면 DB 방언이 도메인에 들어온다.
    endpoint_hash VARCHAR(64)  NOT NULL,

    -- 본문을 암호화하는 데 쓰는 키 둘. 규격이 base64url 로 주고 각각 88자 · 24자 안팎이다.
    --
    -- **식별값이 아니라 열쇠다.** 처리방침 제1조가 「기기 구분값」으로 적고 있어 문면 보완을
    -- 요청해 두었다 (STAR-132 티켓 본문).
    p256dh        VARCHAR(255) NOT NULL,
    auth          VARCHAR(255) NOT NULL,

    -- BaseEntity 를 상속해 얻는다. 같은 기기가 다시 등록하면 키만 갱신되므로 updated_at 이
    -- 실제로 움직인다 — notification_mute 와 갈리는 지점이다.
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),

    -- **주소 하나에 행 하나다. 유저별이 아니라 전역이다.**
    --
    -- 그 주소는 푸시 서비스가 기기에 발급한 것이라 세상에 하나뿐이다. 유저별로 걸면 공용 PC
    -- 에서 A 가 로그아웃하고 B 가 알림을 켤 때 같은 주소가 두 행이 되고, 그때 A 의 알림이
    -- B 가 쓰는 기기로 간다. 전역으로 걸면 등록이 그 행의 주인을 B 로 옮긴다.
    --
    -- 브라우저가 구독을 갈 때(pushsubscriptionchange) 다시 보내는 것도 정상 경로다. 그때마다
    -- 행이 쌓이면 한 기기에 같은 알림이 여러 번 간다.
    CONSTRAINT uq_push_subscription_endpoint UNIQUE (endpoint_hash),

    -- 발송이 「이 사람의 구독 전부」를 읽는다 (NT-13). 탈퇴 정리도 같은 조건이다.
    INDEX idx_push_subscription_user (user_id)

    -- 만료 시각 컬럼을 두지 않는다. 지우는 때가 셋 다 사건이다 — 사용자가 끄거나(DELETE),
    -- 발송이 410 을 받거나(NT-14), 탈퇴하거나. 시각으로 재는 것이 없어 배치도 필요 없다.
    -- 처리방침 제3조의 보유 기간 문면과도 같은 모양이다.
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
