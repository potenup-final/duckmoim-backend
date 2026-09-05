-- 개발용 더미. 세 글이 댓글 개발에 필요한 상태를 다 덮는다.
--   1 OPEN   댓글이 써지는 글
--   2 CLOSED CM-01 차단이 확인돼야 하는 글
--   3 OPEN   행사를 안 고른 글. eventTitle · eventImageUrl 이 둘 다 null 이어야 한다 (API 2-4)
INSERT INTO companion_post (id, host_id, event_id, event_title, event_image_url, title, content,
                            meet_at, meet_place, meet_lat, meet_lng, capacity,
                            status, closed_reason, created_at, updated_at)
VALUES (1, 1, 1, '성수 생일카페', 'https://example.com/event-1.jpg',
        '성수 생카 같이 가실 분', '커피 한 잔 하고 굿즈 교환도 해요.',
        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 3 DAY), '성수역 3번 출구', 37.5445000, 127.0557000, 4,
        'OPEN', NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
       (2, 1, 1, '성수 생일카페', 'https://example.com/event-1.jpg',
        '이미 마감한 글', '정원이 다 찼습니다.',
        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 5 DAY), '성수역 3번 출구', 37.5445000, 127.0557000, 2,
        'CLOSED', 'MANUAL', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
       (3, 4, NULL, NULL, NULL,
        '행사 없이 만나요', NULL,
        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 7 DAY), '홍대입구역 9번 출구', 37.5570000, 126.9250000, NULL,
        'OPEN', NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
