-- 행사 목록 조회(EV-05 · EV-06)가 쓰는 인덱스.
--
-- 정렬 키와 커서 키가 같은 (starts_on, id) 다. 커서 조건은
-- (starts_on, id) > (?, ?) 형태의 튜플 비교라 이 인덱스 하나로
-- 탐색과 정렬이 함께 끝난다. OFFSET 을 쓰지 않으므로 뒤 페이지로
-- 갈수록 느려지지도 않는다.
--
-- id 를 뒤에 붙인 이유 — starts_on 은 중복이 많다. 같은 날 시작하는
-- 행사가 페이지 경계에 걸리면 정렬이 불안정해져 누락·중복이 생긴다.
CREATE INDEX idx_event_starts_on_id ON event (starts_on, id);
