package com.duckmoim.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 밀린 양을 주기적으로 로그에 남긴다 (NT-05).
 *
 * <p><b>알림 폭증을 다시 볼 조건의 유일한 관측 수단이다.</b> 지금 안 넣으면 나중에 「묶어 보낼까」를 판단할 숫자가 없다 — 회고 2026-09-10 이 이 값을
 * 남겨 두라고 적은 이유다.
 *
 * <p><b>발송 주기와 따로 돈다.</b> 발송은 10초라 거기 얹으면 하루 8640 줄이 쌓여 실제로 볼 값을 찾을 수 없다. 분 단위로 떼면 하루 1440 줄이고, 적체는
 * 초 단위로 변하는 값이 아니라 그 해상도로 충분하다.
 *
 * <p><b>0 건일 때도 남긴다.</b> 발송 쪽은 「0건 보냄」을 빼지만 여기는 반대다 — 빼면 <b>「지금 밀리지 않는다」를 확인할 방법이 사라지고</b>, 로그가 없는
 * 것이 「평온하다」인지 「배치가 죽었다」인지 구분되지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationBacklogBatch {

  private final NotificationBacklogService notificationBacklogService;

  /**
   * 밀린 양을 한 줄로 남긴다.
   *
   * <p><b>예외를 밖으로 던지지 않는다.</b> 관측이 스케줄러를 멈추면 관측 때문에 다른 주기 작업까지 잃는다. 발송 배치와 같은 판단이다.
   */
  @Scheduled(cron = "${duckmoim.notification.backlog.cron}")
  public void logBacklog() {
    try {
      NotificationBacklog backlog = notificationBacklogService.measure();

      log.info(
          "[NotificationBacklogBatch.logBacklog] Notification backlog. pending={}, deadLettered={}",
          backlog.pending(),
          backlog.deadLettered());

    } catch (Exception exception) {
      log.error("[NotificationBacklogBatch.logBacklog] Failed to measure backlog.", exception);
    }
  }
}
