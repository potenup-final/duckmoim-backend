-- 개발용 더미. 건수가 아니라 판정 조합으로 골랐다.
-- 이 일곱 명이면 도메인 7.1 비밀 댓글 열람표 다섯 줄과 7.2 lastSeen 다섯 구간,
-- CM-18 availableActions 네 값이 전부 재현된다.
--
--   1 방장            글을 쓴 사람. 남의 비밀 댓글을 볼 수 있는 유일한 일반 유저
--   2 부모댓글작성자  비밀 루트 댓글을 쓰는 사람
--   3 대댓글작성자    2 의 댓글에 답을 다는 사람
--   4 제3자           아무 권한도 없는 사람. 본문이 빠져야 하는 쪽
--   5 탈퇴자          소프트 삭제 필터가 빠진 조회 경로를 드러내는 사람
--   6 관리자          V31 에서 admin_accounts 에 등록된다
--   7 가입미완료      I-02 쓰기 차단 확인용. 닉네임이 아직 없다
INSERT INTO user (id, kakao_user_id, nickname, birth_year, intro, profile_image_url, status,
                  last_seen_at, withdrawn_at, created_at, updated_at)
VALUES (1, 1001, '방장덕후', 1998, '성수 팝업 자주 갑니다', NULL, 'ACTIVE',
        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 HOUR), NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
       (2, 1002, '댓글덕후', 2001, NULL, NULL, 'ACTIVE',
        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 DAY), NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
       (3, 1003, '답글덕후', 2003, NULL, NULL, 'ACTIVE',
        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 DAY), NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
       (4, 1004, '지나가던덕후', 1995, NULL, NULL, 'ACTIVE',
        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 20 DAY), NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
       (5, 1005, '떠난덕후', 1999, NULL, NULL, 'WITHDRAWN',
        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 100 DAY), DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 90 DAY),
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
       (6, 1006, '운영자', 1990, NULL, NULL, 'ACTIVE',
        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR), NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
       (7, 1007, NULL, NULL, NULL, NULL, 'PENDING_SIGNUP_INFO',
        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR), NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
