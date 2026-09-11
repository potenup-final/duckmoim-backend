package com.duckmoim.notification.service;

import com.duckmoim.notification.domain.NotificationCursor;
import java.util.List;

/**
 * 알림함 한 페이지 (NT-08).
 *
 * <p>{@code Slice}·{@code Page} 를 service 시그니처에 노출하지 않는다는 규칙(아키텍처-컨벤션.md 「service」)에 따라 둔 결과 객체다.
 *
 * <p><b>판정 입력이 함께 실리지 않는다.</b> 댓글 목록의 결과 객체는 방장 번호를 함께 올린다 — 본문을 보여줄지가 그 값으로 갈리기 때문이다 (도메인-모델링.md
 * 「7.1 가시성과 권한」). 알림에는 가릴 필드가 없다. 목록에 오른 순간 이미 내 것이고, 남의 것은 질의가 걸러서 여기까지 오지 않는다.
 *
 * @param nextCursor 마지막 페이지면 null 이다
 */
public record NotificationSlice(
    List<NotificationView> items, NotificationCursor nextCursor, boolean hasNext) {}
