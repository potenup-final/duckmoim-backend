-- 크롤러가 이 행사를 마지막으로 본 시각 (EV-03 · API-설계 D-7).
--
-- 벌크 적재는 upsert 만 한다. 원본에서 내려간 행사는 요청에 실려 오지 않을 뿐이라
-- DB 는 그 사실을 알 방법이 없고, 행이 영원히 남아 목록에 뜬다. 기간이 지난 것은
-- ends_on >= 오늘 필터가 거르지만 「취소됐는데 기간이 남은」 행사는 걸리지 않는다 —
-- 2026-09-08 점검에서 V3 시드 40건 중 4건이 실제로 그 상태였다.
--
-- 그래서 적재마다 이 값을 갱신하고, 오래 안 잡힌 행을 목록에서 숨긴다.
--
-- last_seen_at 이 아니라 last_crawled_at 이다. user 에 이미 last_seen_at 이 있고
-- (V10) 그것은 「사람이 마지막으로 접속한 시각」이다. 테이블이 달라 충돌하지는
-- 않지만 코드에서 lastSeenAt 만 보면 어느 쪽인지 읽히지 않는다. 갱신 주체가
-- 크롤러라는 사실을 이름에 남긴다.
--
-- 지우지 않고 숨기는 이유 — 즐겨찾기가 id 기준 localStorage 라(EV-11) 행을 지우면
-- 사용자의 즐겨찾기가 조용히 깨지고, 상세 조회는 끝난 행사도 반환해야 한다(EV-07).
ALTER TABLE event
    ADD COLUMN last_crawled_at DATETIME(6) NULL AFTER region_id;

-- 기존 행을 적용 시각으로 채운다.
--
-- NULL 을 「한 번도 안 잡힌 것」으로 읽어 숨기면, 이 마이그레이션이 적용되는 순간
-- V3 시드 전체가 목록에서 사라진다. 첫 적재가 성공할 때까지 서비스가 빈 목록을
-- 보게 되고, 그 첫 적재는 다음 새벽 04:00 잡이다.
--
-- 채워 두면 임계 시간만큼의 여유가 생긴다 — 첫 적재가 실패해도 그 안에 고치면 된다.
-- 시드가 「최초 1회 부트스트랩」이라는 성격(ISR 계획 4-3)과도 맞는다.
UPDATE event
SET last_crawled_at = UTC_TIMESTAMP(6)
WHERE last_crawled_at IS NULL;

-- 채운 뒤에 NOT NULL 로 조인다. 이 컬럼 없이 들어오는 INSERT 를 막는 것이 목적이다 —
-- 기본값을 주면 적재 경로가 값을 빼먹어도 조용히 「방금 본 행사」가 된다.
ALTER TABLE event
    MODIFY COLUMN last_crawled_at DATETIME(6) NOT NULL;
