package com.duckmoim.chat.service;

import java.time.LocalDateTime;

/**
 * 방 목록 한 줄로 조립한 결과 (CH-05).
 *
 * <p>infra 의 {@code ChatRoomSummary} 를 presentation 에 그대로 넘기지 않는다 — presentation 은 service 만 참조할 수
 * 있다 (아키텍처 컨벤션 「레이어 의존성」).
 */
public record ChatRoomSummaryView(
    Long roomId, Long postId, String postTitle, LocalDateTime meetAt, long memberCount) {}
