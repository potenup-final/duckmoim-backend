-- 채팅 이미지의 EXIF 제거 (CH-16). Chat 대역(V700~V799)의 여섯째 파일이다.
--
-- 명세: 「사진에 촬영 좌표가 들어 있다. 개인위치정보를 수집하지 않기로 한 판단을 뒷문으로
-- 되돌리지 않는다. 업로드 뒤 비동기 워커가 벗긴다」.

-- EXIF 처리 상태. PENDING → STRIPPED, 못 벗기면 FAILED.
--
-- **수명주기 status 와 따로 둔다.** status(PENDING → CONFIRMED → ATTACHED, 정리는
-- DELETING)에 「EXIF 처리 중」을 끼우면 전송이 워커 한 주기를 기다려야 한다 — 사용자는
-- 확정 직후에 보낸다. 따로 두면 전송은 안 기다리고, 대신 **보여주는 쪽(CH-15)이
-- STRIPPED 만 서명한다.** 두 축이 서로의 조건을 모른다.
--
-- **기본값이 PENDING 인 것이 이 파일의 판단이다.** STAR-115 가 이미 배포돼 있어 좌표 든
-- 사진이 운영에 있을 수 있다. 기존 행이 전부 워커 대상이 된다.
--
-- FAILED 는 영구히 보여주지 않는다. 형식을 알 수 없거나 구조가 깨져 원본에 무엇이 남았는지
-- 모르는 파일이다.
ALTER TABLE chat_image
    ADD COLUMN exif_status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    -- 실패 횟수. 선점은 시도가 아니라 올리지 않는다 (ADR 0008 — 올리면 재시도를 하나
    -- 까먹는다).
    ADD COLUMN exif_attempts        INT         NOT NULL DEFAULT 0,

    -- 「이 시각 전에는 집지 마라」. 백오프와 선점 리스가 같은 칸을 쓴다 (ADR 0008 이
    -- notification_outbox.next_attempt_at 에 대해 같은 판단을 했다 — 리스는 시각이라
    -- 워커가 죽어도 저절로 풀린다).
    ADD COLUMN exif_next_attempt_at DATETIME(6) NULL;

-- 워커가 집는 질의다.
--
--   WHERE exif_status = 'PENDING' AND status IN ('CONFIRMED','ATTACHED')
--     AND (exif_next_attempt_at IS NULL OR exif_next_attempt_at <= ?)
--   ORDER BY id  LIMIT ?  FOR UPDATE SKIP LOCKED
--
-- (exif_status, id) 인 이유는 notification_outbox 의 (status, id) 와 같다 — 대부분의 행이
-- 곧 STRIPPED 가 되므로 선두 컬럼이 범위를 좁히고, id 가 정렬을 인덱스로 끝낸다.
-- 인덱스가 없으면 FOR UPDATE 가 풀스캔 잠금이 되어 전송의 attach 가 워커를 기다린다.
CREATE INDEX idx_chat_image_exif_pending ON chat_image (exif_status, id);
