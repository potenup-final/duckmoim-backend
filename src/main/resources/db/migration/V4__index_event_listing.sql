-- 행사 목록 조회(EV-05 · EV-06)가 쓰는 인덱스.
--
-- 정렬 키와 커서 키가 같은 (ends_on, id) 다. 커서 조건은
-- (ends_on, id) > (?, ?) 형태의 튜플 비교라 이 인덱스 하나로
-- 탐색과 정렬이 함께 끝난다. 목록이 항상 거는 ends_on >= today
-- 조건도 같은 선두 컬럼을 쓴다.
--
-- id 를 뒤에 붙인 이유 — ends_on 은 중복이 많다. 같은 날 끝나는
-- 행사가 페이지 경계에 걸리면 정렬이 불안정해져 누락·중복이 생긴다.
CREATE INDEX idx_event_ends_on_id ON event (ends_on, id);
