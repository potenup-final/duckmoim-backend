package com.duckmoim.notification.service;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.domain.NotificationMute;
import com.duckmoim.common.infra.NotificationMuteRepository;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 끈 종류를 한 트랜잭션에서 갈아 끼운다 (NT-11).
 *
 * <p><b>트랜잭션 경계가 이 클래스다.</b> 재시도 판정은 {@link NotificationSettingService} 가 진다 — 데드락으로 죽은 트랜잭션은 <b>이미
 * 롤백된 뒤</b>라 같은 트랜잭션 안에서 다시 시도할 수 없다. 그래서 <b>잡는 쪽이 트랜잭션 밖에 있어야 한다.</b>
 *
 * <p>빈을 둘로 나눈 것은 {@code ChatMessageSendService} / {@code ChatMessageWriter} 와 같은 배치이고 근거도 같다 — 같은
 * 클래스 안에서 자기를 부르면 프록시를 지나지 않아 {@code @Transactional} 이 걸리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class NotificationMuteWriter {

  private final NotificationMuteRepository notificationMuteRepository;

  /**
   * 지우고 다시 넣는다.
   *
   * <p><b>삭제가 벌크여야 이 순서가 성립한다</b> (PR #148 리뷰). 파생 삭제는 {@code DELETE} 를 flush 까지 미루는데 {@code
   * IDENTITY} 인 {@code INSERT} 는 즉시 나가서, 이미 끈 종류를 다시 보내면 {@code uq_notification_mute} 에 걸린다.
   *
   * @throws org.springframework.dao.PessimisticLockingFailureException 같은 사람의 요청이 동시에 들어와 데드락으로 진
   *     경우. {@link NotificationSettingService} 가 잡아 다시 시도한다
   * @throws org.springframework.dao.DataIntegrityViolationException 남이 먼저 커밋해 같은 키를 다시 넣게 된 경우. 같은
   *     쪽이 잡는다
   */
  @Transactional
  public void replace(Long userId, Set<NotificationKind> mutedKinds) {
    notificationMuteRepository.deleteByUserId(userId);

    mutedKinds.forEach(kind -> notificationMuteRepository.save(NotificationMute.of(userId, kind)));
  }
}
