-- Identity / Auth 대역(V10~V19). V10 · V11 · V12 가 쓰였고 다음이 V13 이다.
-- 고르기 전에 열려 있는 PR 도 확인했다 — #45 가 V34(Report), 그 밖에 대역 안을 쓰는 것은 없다.
--
-- 회전을 「지우기」에서 「표시하기」로 바꾼다 (AU-03).
--
-- 왜 지우면 안 되는가 — 지우고 나면 「방금 회전됐다」와 「오래 전에 죽었다」를 구분할 수 없다.
-- 그 구분이 없으면 탭 둘이 같은 Refresh 로 동시에 재발급할 때(앱 복귀 시 대기 요청 둘이 흔히
-- 그렇다) 진 쪽이 재사용으로 판정해 이긴 쪽이 방금 받은 세션까지 폐기한다. 사용자에게는
-- 「재발급 성공 직후 원인 없이 튕김」으로 보이고, 훔친 토큰이 없어도 재현된다 — 실측했다.
ALTER TABLE refresh_token
    -- NULL 이면 아직 살아 있는 토큰, 값이 있으면 회전된 토큰이다.
    -- 회전 시점에서 유예(RefreshToken.ROTATION_GRACE) 안의 재요청은 이중 제출로 보고
    -- 폐기하지 않는다. 유예를 넘긴 재요청이 재사용이다.
    ADD COLUMN rotated_at DATETIME(6) NULL AFTER expires_at;

-- 살아 있는 토큰만 훑는 경로가 이 컬럼으로 걸러진다. user_id 인덱스는 V12 에 있다.
CREATE INDEX ix_refresh_token_rotated_at ON refresh_token (rotated_at);
