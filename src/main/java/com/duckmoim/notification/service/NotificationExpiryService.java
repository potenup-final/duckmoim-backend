package com.duckmoim.notification.service;

import com.duckmoim.notification.infra.NotificationRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료된 알림을 지운다 (NT-11a).
 *
 * <p><b>청크 하나가 트랜잭션 하나다.</b> 30일 치를 한 트랜잭션에 담으면 롤백 단위가 통째가 되고, 잠긴 행이 커밋 전까지 풀리지 않아 그 수신자의 알림함 조회와
 * 읽음 처리가 기다린다. 배포 직후 첫 실행은 그동안 밀린 것을 전부 만나므로 그 건수가 얼마인지 알 수 없다.
 *
 * <p><b>주기와 반복은 여기 없다.</b> {@code NotificationExpiryBatch} 가 진다 — 이 메서드가 트랜잭션 경계이고, 같은 클래스 안에서 자기를
 * 부르면 프록시를 지나지 않아 {@code @Transactional} 이 걸리지 않는다. 빈 둘로 나누는 이유가 그것이다 ({@code
 * MeetTimePassedCloseService} 와 같은 배치다).
 *
 * <p><b>경계 시각을 받는다.</b> {@code Clock} 을 여기서 읽지 않는 것은 「지난 알림」과 「안 지난 알림」의 경계가 검증 대상이라서다 — 시각을 인자로 두면
 * 검사가 실행 시각에 결과를 맡기지 않는다.
 *
 * <p><b>잠그지 않는다.</b> 인스턴스가 둘이라 이 배치도 둘이 도는데, 삭제가 멱등이라 막을 것이 없다 (ADR 0009). 두 인스턴스가 같은 순서로 훑으므로 락 획득
 * 순서가 같아 데드락도 나지 않는다.
 */
@Service
@RequiredArgsConstructor
public class NotificationExpiryService {

  private final NotificationRepository notificationRepository;

  /**
   * 청크 하나만큼 지운다.
   *
   * @param cutoffInUtc 이 시각보다 <b>앞서</b> 만들어진 알림이 만료다. 경계에 정확히 걸친 것은 남는다
   * @param chunk 한 번에 지울 최대 건수
   * @return 집은 수와 지운 수. 둘이 갈리는 이유는 {@link NotificationExpiry} 에 있다
   */
  @Transactional
  public NotificationExpiry deleteChunk(LocalDateTime cutoffInUtc, int chunk) {
    List<Long> expired =
        notificationRepository.findExpiredIds(cutoffInUtc, PageRequest.ofSize(chunk));

    if (expired.isEmpty()) {
      return new NotificationExpiry(0, 0);
    }

    return new NotificationExpiry(expired.size(), notificationRepository.deleteAllByIdIn(expired));
  }
}
