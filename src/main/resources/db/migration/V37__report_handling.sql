-- Comment / Safety 대역(V30~V39). V30~V36 이 쓰였으므로 V37 이다.
--
-- 한 파일에 컬럼 추가와 인덱스를 함께 담는다. 대역에 V37 · V38 · V39 셋만 남았고
-- STAR-80(AD-04 제재)이 sanction 표로 같은 대역을 쓴다. 둘로 쪼개면 그 티켓이 쓸
-- 자리가 하나 줄어든다.

-- 신고 처리 결과 (AD-03).
--
-- 「이력은 신고 자체가 진다」 (도메인 1.1 각주). 감사 로그에 REPORT 가 없는 이유가
-- 이것이고, 그래서 처리 흔적이 여기 남는다.
--
-- result 를 enum 과 자유 메모로 나눈 이유 — 도메인 5장이 미결로 남긴 자리다.
-- 자유 문자열 하나면 「제재 없이 종결한 신고가 몇 건인가」를 셀 수 없고, enum 하나면
-- 관리자가 판단 맥락을 남길 자리가 없다.
ALTER TABLE report
    ADD COLUMN result      VARCHAR(30)  NULL AFTER status,
    ADD COLUMN memo        VARCHAR(500) NULL AFTER result,

    -- 누가 처리했는가. 애그리게이트 밖은 ID 로만 참조한다 (도메인 3.2).
    ADD COLUMN handled_by  BIGINT       NULL AFTER memo,
    ADD COLUMN handled_at  DATETIME(6)  NULL AFTER handled_by;

-- 백오피스 목록(AD-02)이 쓰는 인덱스. V34 가 「그 인덱스는 정렬 키를 쥔 티켓이
-- 붙인다」 고 미뤄 둔 것이 이 티켓이다.
--
--   전체    WHERE (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC
--   필터    WHERE status = ? AND (created_at, id) < (?, ?) ORDER BY 같음
--
-- status 를 선두에 둔다. 필터가 있을 때 탐색과 정렬이 이 인덱스 하나로 끝나고,
-- 필터가 없을 때는 어차피 전량을 최신순으로 훑으므로 선두 컬럼을 못 써도 손해가 없다.
-- 백오피스는 건수가 작고 관리자가 넷이라 그 쪽을 최적화할 이유가 없다.
--
-- id 를 뒤에 붙인 이유 — created_at 은 중복이 생긴다. 같은 시각에 접수된 신고가
-- 페이지 경계에 걸리면 정렬이 불안정해져 누락 · 중복이 난다. V33 · V4 가 같은 이유로
-- 같은 모양을 썼다.
CREATE INDEX ix_report_status_created_id ON report (status, created_at, id);
