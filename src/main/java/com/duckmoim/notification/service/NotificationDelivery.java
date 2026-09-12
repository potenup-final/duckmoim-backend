package com.duckmoim.notification.service;

import com.duckmoim.common.domain.NotificationKind;

/**
 * 한 건을 채널들에 보내는 데 필요한 값 (ADR 0010).
 *
 * <p><b>아웃박스 엔티티를 트랜잭션 밖으로 내보내지 않으려고 둔다.</b> 푸시는 T1 의 트랜잭션이 끝난 뒤에 돌므로, 엔티티를 그대로 넘기면 준영속 상태의 객체를 만지게
 * 된다. 필요한 값만 복사해 나간다.
 *
 * <p>담긴 것이 아웃박스 행이 실어 온 전부다 — 워커는 Companion 에 물어볼 수 없다 (도메인-모델링.md 「2. 바운디드 컨텍스트」).
 */
public record NotificationDelivery(
    Long outboxId, Long recipientId, NotificationKind kind, Long postId, Long commentId) {}
