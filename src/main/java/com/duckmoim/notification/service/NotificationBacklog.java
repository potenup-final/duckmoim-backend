package com.duckmoim.notification.service;

/**
 * 지금 알림이 얼마나 밀려 있는가 (NT-05).
 *
 * <p><b>둘을 한 덩이로 읽는 것이 요점이다.</b> 갈라 두면 「밀리는 중」과 「포기가 늘는 중」이 구분되지 않는다 — 대기가 늘면 워커가 못 따라가는 것이고, DLQ 가
 * 늘면 보낼 수 없는 건이 쌓이는 것이라 손댈 자리가 다르다.
 *
 * @param pending 아직 못 보낸 건. 선점되어 처리 중인 것도 포함한다
 * @param deadLettered 시도를 다 써서 DLQ 로 옮겨진 건의 누적
 */
public record NotificationBacklog(long pending, long deadLettered) {}
