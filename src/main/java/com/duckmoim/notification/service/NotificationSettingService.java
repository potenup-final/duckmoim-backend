package com.duckmoim.notification.service;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.domain.NotificationMute;
import com.duckmoim.common.infra.NotificationMuteRepository;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 종류별 수신 설정을 읽고 쓴다 (NT-11).
 *
 * <p><b>「끈 종류」로 말한다.</b> 화면은 토글 셋을 그리지만 저장은 끈 것만 남기므로 (V807), 켜짐·꺼짐 셋을 이 계층까지 들고 오면 같은 상태를 두 모양으로
 * 다루게 된다. 켜짐 셋으로 뒤집는 것은 presentation 이 한다.
 *
 * <p><b>저장은 지우고 다시 넣는다.</b> 계약이 {@code PUT}(전체 수정)이라 들어온 것이 곧 새 상태다. 무엇이 늘고 줄었는지 따져 부분만 고치면 계산이 하나
 * 더 생기는데, 한 사람의 행이 많아야 종류 수(셋)다.
 *
 * <p><b>여기서 「누구의 설정인가」를 판정하지 않는다.</b> 회원번호가 경로가 아니라 인증 주체에서 오므로 남의 것을 가리킬 길이 없다 — 알림함이 {@code I-24}
 * 를 지키는 방식과 같다 (API-설계.md 「5. 결정 사항」 D-14).
 */
@Service
@RequiredArgsConstructor
public class NotificationSettingService {

  private final NotificationMuteRepository notificationMuteRepository;

  /**
   * 이 사람이 끈 종류.
   *
   * <p><b>한 번도 만지지 않았으면 빈 집합이다</b> — 행이 없는 것이 곧 「전부 받는다」다 (V807). 가입 시점에 아무것도 만들지 않는 것이 그래서 가능하다.
   */
  @Transactional(readOnly = true)
  public Set<NotificationKind> findMutedKinds(Long userId) {
    return copyOf(notificationMuteRepository.findMutedKinds(userId));
  }

  /**
   * 끈 종류를 이 목록으로 바꾼다.
   *
   * <p><b>같은 것을 두 번 보내도 결과가 같다.</b> 지우고 다시 넣으므로 {@code uq_notification_mute} 에 부딪히지 않는다.
   *
   * <p><b>이미 만들어진 알림은 건드리지 않는다.</b> 설정은 앞으로 올 것에만 걸린다 — 티켓의 완료 조건이 그것을 명시적으로 막는다. 여기서 지우면 끈 사람이 「어제
   * 온 알림이 사라졌다」를 겪는다.
   */
  @Transactional
  public void replaceMutedKinds(Long userId, Set<NotificationKind> mutedKinds) {
    notificationMuteRepository.deleteByUserId(userId);

    mutedKinds.forEach(kind -> notificationMuteRepository.save(NotificationMute.of(userId, kind)));
  }

  /** 빈 목록에서도 {@code EnumSet} 을 만들려면 타입을 줘야 한다. */
  private static Set<NotificationKind> copyOf(Iterable<NotificationKind> kinds) {
    Set<NotificationKind> copy = EnumSet.noneOf(NotificationKind.class);
    kinds.forEach(copy::add);

    return copy;
  }
}
