package com.duckmoim.notification.service;

import com.duckmoim.common.domain.OutboxStatus;
import com.duckmoim.common.infra.NotificationOutboxRepository;
import com.duckmoim.notification.infra.NotificationOutboxDlqRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림이 얼마나 밀려 있는지 센다 (NT-05).
 *
 * <p><b>발송과 같은 빈에 두지 않는다.</b> 세는 일은 발송이 멈춰 있을 때도 돌아야 한다 — 오히려 그때 가장 보고 싶은 값이다. 한 빈에 묶으면 발송이 죽으면서
 * 관측도 함께 죽는다.
 *
 * <p><b>읽기 전용 한 트랜잭션이다.</b> 두 값을 따로 읽으면 그 사이에 워커가 건을 옮겨 대기와 DLQ 가 서로 다른 순간을 가리킨다. 한 줄에 실릴 두 숫자가 같은
 * 순간이어야 「대기가 줄고 DLQ 가 늘었다」를 믿을 수 있다.
 */
@Service
@RequiredArgsConstructor
public class NotificationBacklogService {

  private final NotificationOutboxRepository outboxRepository;
  private final NotificationOutboxDlqRepository dlqRepository;

  /** 지금 밀린 양을 읽는다. */
  @Transactional(readOnly = true)
  public NotificationBacklog measure() {
    return new NotificationBacklog(
        outboxRepository.countByStatus(OutboxStatus.PENDING), dlqRepository.count());
  }
}
