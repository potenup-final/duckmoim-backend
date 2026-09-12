package com.duckmoim.notification.service;

import com.duckmoim.common.domain.NotificationKind;
import java.time.LocalDateTime;

/**
 * 알림 한 건의 조회 결과 (NT-08).
 *
 * <p>엔티티를 service 의 public 시그니처에 노출하지 않는다는 규칙(아키텍처-컨벤션.md 「service」)에 따라 둔 결과 객체다. 값 객체와 enum 은
 * presentation 이 참조해도 된다 (같은 문서 「의존성 방향」 3번).
 *
 * <p><b>{@code readAt} 이 아니라 {@code read} 다.</b> 「읽었나」만 화면으로 나가고 시각은 표에만 남는다 (V801 주석 · API-설계.md
 * 「2-10. 알림 (Notification) · 2차」). 그 판정은 도메인의 {@code isUnread} 가 쥐고 있고 여기는 그 결과를 나른다.
 *
 * <p><b>대상 참조를 넷 다 편다.</b> 종류마다 채워지는 짝이 다르고 (NT-07) 해당 없는 쪽은 {@code null} 로 내려간다 — API-설계.md 「2-10.
 * 알림 (Notification) · 2차」 가 정한 응답 모양 그대로다.
 *
 * @param createdAt 저장된 값 그대로 UTC 다. KST 로 옮기는 것은 presentation 이 한다
 */
public record NotificationView(
    Long id,
    NotificationKind kind,
    Long postId,
    Long commentId,
    Long roomId,
    Long messageId,
    boolean read,
    LocalDateTime createdAt) {}
