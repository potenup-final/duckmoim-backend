-- 개발용 더미. V11 의 6 번 회원을 관리자로 만든다.
-- 운영에서는 이 표에 DB 로 직접 넣는다 (API 설계 D-5).
INSERT INTO admin_accounts (kakao_user_id, granted_at, created_at, updated_at)
VALUES (1006, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
