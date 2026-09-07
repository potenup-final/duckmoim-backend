-- Comment / Safety 대역(V30~V39). V30 · V31 이 admin_accounts 로 선점되어 다음은 V32 다.
-- 규칙 문서의 「현재」 열은 이 대역을 「비어 있음」으로 적고 있어 낡았다 (실제 파일이 정확하다).
--
-- CM-01 · CM-02 · CM-03 의 저장 자리다. 조회(CM-04 ~ CM-08 · CM-20)는 별도 티켓이다.
CREATE TABLE comment
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,

    -- 애그리게이트 밖은 ID 로만 참조한다 (도메인 3.2). FK 를 걸지 않는다.
    -- Comment 는 CompanionPost 밖이고 (도메인 3.1), 댓글 수는 어디에도 저장하지 않아
    -- 작성이 companion_post 행을 건드리지 않는다.
    post_id    BIGINT       NOT NULL,
    author_id  BIGINT       NOT NULL,

    -- 대댓글은 별도 타입이 아니라 parent_id 가 있는 Comment 다 (도메인 1장 유비쿼터스 언어).
    -- 깊이 1단계 고정(I-06)의 이중 방어는 부모의 parent_id 확인이라 DB 제약이 아니다.
    parent_id  BIGINT       NULL,

    content    VARCHAR(500) NOT NULL,

    -- boolean 에 is 접두어를 붙이지 않는다 (API 컨벤션). 권한자에게만 본문이 보이는 댓글 (CM-03).
    secret     BOOLEAN      NOT NULL,

    -- 소프트 삭제를 deleted_at 이 아니라 status 로 표현한다 (도메인 6장 라이프사이클).
    -- ACTIVE · DELETED · BLINDED.
    status     VARCHAR(20)  NOT NULL,

    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,

    PRIMARY KEY (id)
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 한 모집글의 댓글을 찾는 경로는 조회 방식과 무관하게 확실하다. 목록의 정렬 키
-- (createdAt, id) 와 루트 기준 커서에 맞춘 복합 인덱스는 CM-06 · CM-07 티켓이 붙인다
-- (API 설계 「3. 커서 정의」). 여기서 미리 지어내면 쓰지 않는 인덱스가 남는다.
CREATE INDEX ix_comment_post_id ON comment (post_id);
