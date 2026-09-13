package com.duckmoim.notification.service;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.notification.exception.NotificationErrorCode;
import com.duckmoim.notification.infra.NotificationRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 읽음 처리와 안 읽은 수 (NT-09 · NT-10).
 *
 * <p><b>조회와 갈라 둔다.</b> {@link NotificationQueryService} 는 읽기 전용 한 덩어리인데 여기는 쓰기가 둘 섞여 있고, 배지 하나만 필요한
 * 화면이 목록 서비스를 끌고 오게 되는 것도 피한다.
 *
 * <p><b>권한 판정이 여기 없다.</b> 셋 다 「내 것」에만 닿는 질의라 「볼 수 있는가」를 물을 상대가 없다 — 수신자가 인자가 아니라 <b>질의 모양</b>에 박혀
 * 있다 ({@code NotificationRepository}). {@code I-24} 가 이중 방어 없이 지켜지는 방식이 그것이고, 판정으로 바꾸면 한 번 빠뜨렸을 때
 * 아무것도 잡지 못한다.
 *
 * <p><b>시각은 여기서 뜬다.</b> 개별 읽음과 전체 읽음이 같은 출처를 쓰고, 저장소는 받은 값을 그대로 쓴다 — 그래야 검사가 시각을 고정할 수 있다. 저장은 UTC
 * 이고 표기는 presentation 이 정한다.
 *
 * <p><b>둘 다 엔티티를 불러오지 않는다.</b> 읽음 전이가 조건부 UPDATE 라 영속성 컨텍스트를 지날 일이 없다. 「이미 읽었나」를 메모리에서 판정하면 조회와 커밋
 * 사이에 다른 쪽이 지나갔을 때 먼저 찍힌 시각을 덮어쓴다 ({@code NotificationRepository#markRead}).
 */
@Service
@RequiredArgsConstructor
public class NotificationReadService {

  private final NotificationRepository notificationRepository;

  /**
   * 알림 하나를 읽음으로 바꾼다 (NT-09).
   *
   * <p><b>두 걸음인 이유는 404 다.</b> 조건부 UPDATE 하나로는 「없거나 남의 것」과 「이미 읽은 것」이 똑같이 0행으로 돌아와, 404 를 내야 할 요청과
   * 성공이어야 할 요청이 구별되지 않는다. 그래서 존재만 먼저 묻고, 읽음 판정은 UPDATE 에 맡긴다.
   *
   * <p><b>없는 알림과 남의 알림이 같은 예외로 끝난다.</b> 저장소가 둘을 구별하지 않고 {@code false} 로 돌려주므로 (D-14 ②) 여기서 갈라 볼 값
   * 자체가 없다.
   *
   * <p><b>바뀐 행 수를 보지 않는다.</b> 0 은 이미 읽은 알림이고 그것도 성공이다 — 「다시 읽음」의 판정은 UPDATE 의 {@code read_at is
   * null} 이 쥔다.
   *
   * <p>존재 확인과 UPDATE 사이에 만료 배치(NT-11a)가 그 행을 지우면 0행이 되어 성공으로 끝난다. <b>그래도 맞다</b> — 사용자가 지우려던 배지가 사라진
   * 것은 같고, 없는 알림에 404 를 내려면 그 틈을 잠가야 하는데 그 대가가 이 자리에서 더 크다.
   */
  @Transactional
  public void markRead(long recipientId, long notificationId) {
    if (!notificationRepository.existsByIdAndRecipientId(notificationId, recipientId)) {
      throw new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
    }

    notificationRepository.markRead(notificationId, recipientId, LocalDateTime.now(ZoneOffset.UTC));
  }

  /**
   * 내 안 읽은 알림을 전부 읽음으로 바꾼다 (NT-09).
   *
   * <p><b>바뀐 행 수를 돌려주지 않는다.</b> 응답이 빈 본문이라 (API-설계.md 「2-10. 알림 (Notification) · 2차」) 화면이 쓸 곳이 없고,
   * 내보내면 배지 값처럼 읽힌다 — 배지는 {@link #countUnread} 가 전담한다.
   *
   * <p>안 읽은 알림이 하나도 없어도 성공이다. 몇 번을 불러도 같은 결과여야 하는 동작이다.
   */
  @Transactional
  public void markAllRead(long recipientId) {
    notificationRepository.markAllRead(recipientId, LocalDateTime.now(ZoneOffset.UTC));
  }

  /** 내 안 읽은 알림 건수 (NT-10). 배지에 그대로 나간다. */
  @Transactional(readOnly = true)
  public long countUnread(long recipientId) {
    return notificationRepository.countByRecipientIdAndReadAtIsNull(recipientId);
  }
}
