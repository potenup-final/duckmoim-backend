-- 채팅 이미지 (CH-14 · CH-17). Chat 대역(V700~V799)의 다섯째 파일이다.
--
-- **서버가 파일을 받지 않는다.** 브라우저가 S3 로 직접 올리고 서버는 서명과 확인만 한다
-- (명세 2-1 의 CH-14 가 「AU-08 과 같은 구조」로 정했다). 그래서 이 표에 바이트가 없고
-- 객체 키와 메타데이터만 있다.
CREATE TABLE chat_image
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,

    -- 애그리게이트 밖은 ID 로만 참조한다 (도메인 3.2). FK 를 걸지 않는다.
    --
    -- **방 번호를 들고 있어야 확정과 전송이 멤버 판정을 할 수 있다.** 프로필 이미지는
    -- 키(profile/{userId}/…)가 소유자를 말해서 DB 행이 필요 없었는데, 채팅은 「이 방의
    -- 멤버인가」를 물어야 하고 그 답은 키에 없다.
    room_id       BIGINT       NOT NULL,
    uploader_id   BIGINT       NOT NULL,

    -- S3 객체 키. chat/{roomId}/{uuid}.{ext} 다.
    --
    -- **공개 주소를 저장하지 않는다.** CH-15(이미지 접근 제어)가 「공개 주소를 쓰지 않는다.
    -- 조회할 때마다 방 멤버인지 판정한 뒤 짧은 TTL 의 서명을 발급한다」로 정했고 그것은 별
    -- 티켓이다. 여기에 주소를 박으면 저장된 값 전부가 그 티켓의 마이그레이션 대상이 된다 —
    -- profile_image_url 이 공개 주소인 것과 갈리는 자리다.
    --
    -- 255자는 접두어 + 방 번호 + UUID(36) + 확장자에 넉넉한 값이다.
    object_key    VARCHAR(255) NOT NULL,

    -- 발급 때는 클라이언트가 **선언한** 값, 확정 때는 S3 에 물어본 **실제** 값이다.
    -- 두 시점에 같은 정책으로 검사한다 (ProfileImagePolicy 와 같은 근거) — 선언은
    -- 거짓말일 수 있고, 실제 값은 올라온 뒤에만 알 수 있다.
    content_type  VARCHAR(50)  NOT NULL,

    -- 확정 전에는 0 이다. 선언한 크기를 저장하지 않는 것은 그 값이 검사에만 쓰이고
    -- 남겨 두면 「확정 전에도 크기를 아는 것처럼」 보이기 때문이다.
    byte_size     BIGINT       NOT NULL DEFAULT 0,

    -- PENDING → CONFIRMED → ATTACHED.
    --
    -- **발급 시점에 행을 만드는 것이 이 표의 판단이다.** 고아가 두 종류라서다 —
    -- ① 올리고 확정 안 함 ② 확정했는데 전송 안 함. 확정 때 행을 만들면 ①은 DB 에 흔적이
    -- 없어 배치가 존재 자체를 모르고, S3 에 영구히 남는다.
    --
    -- AU-08 은 발급 때 DB 를 안 건드린다. 그쪽은 지울 필요가 없어 추적할 이유가 없었다.
    status        VARCHAR(20)  NOT NULL,

    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),

    -- 같은 키로 두 행이 생기면 하나를 지울 때 남은 행이 없는 객체를 가리킨다.
    -- 키에 UUID 가 들어가 실수로 겹칠 일은 없지만, 그 전제를 표가 지킨다.
    CONSTRAINT uq_chat_image_object_key UNIQUE (object_key),

    -- 고아 정리 배치의 질의다 (CH-17).
    --
    --   WHERE status <> 'ATTACHED' AND created_at < ?  ORDER BY id  LIMIT ?
    --
    -- status 를 선두에 두는 것은 ATTACHED 가 시간이 갈수록 대다수가 되기 때문이다.
    -- created_at 을 선두로 두면 그 대다수를 훑고 나서 상태로 거른다.
    KEY idx_chat_image_orphan (status, created_at)
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 메시지가 이미지를 갖는다 (CH-14).
--
-- **방향이 메시지 → 이미지다.** 반대로 chat_image.message_id 를 두는 방법도 있었지만,
-- 목록 조회가 「메시지마다 그 이미지」를 붙이는 모양이라 이쪽이 PK 조인이 된다 (반대면
-- message_id 에 인덱스가 하나 더 필요하다).
--
-- NULL 이 기본이다. 이미지 없는 메시지가 대다수다.
--
-- FK 를 걸지 않는 것은 이 표의 다른 참조들과 같다. 다만 그 대가로 **고아 정리 배치가
-- ATTACHED 를 건드리지 않는다는 규칙**이 이 열의 정합성을 지킨다.
ALTER TABLE chat_message
    ADD COLUMN image_id BIGINT NULL;

-- content 를 NULL 허용으로 바꾸지 않는다.
--
-- 이미지만 보내는 메시지가 생기지만 그때 본문은 빈 문자열이다. NULL 을 허용하면
-- 「빈 문자열」과 「없음」 두 표현이 생기고, 조회 조립이 둘을 다 다뤄야 한다.
-- 「본문과 이미지가 둘 다 비면 안 된다」는 Message 애그리게이트가 본다.
