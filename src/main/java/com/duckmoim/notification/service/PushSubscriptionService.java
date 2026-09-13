package com.duckmoim.notification.service;

import com.duckmoim.identity.domain.UserWithdrawn;
import com.duckmoim.notification.domain.PushSubscription;
import com.duckmoim.notification.infra.PushSubscriptionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 푸시 구독을 등록하고 해제한다 (NT-12).
 *
 * <p><b>둘 다 몇 번을 불러도 같은 결과다.</b> 등록은 같은 기기면 갱신이고 해제는 없는 것을 지워도 성공이다 — 브라우저가 구독을 갈 때마다 다시 보내는 것이 정상
 * 경로라 (`pushsubscriptionchange`) 멱등이 아니면 한 기기에 같은 알림이 여러 번 간다.
 *
 * <p><b>구독이 남의 것이 될 수 없다.</b> 회원번호가 경로가 아니라 인증 주체에서 오고, 해제 조건에도 함께 건다.
 */
@Service
@RequiredArgsConstructor
public class PushSubscriptionService {

  private final PushSubscriptionRepository pushSubscriptionRepository;
  private final Clock clock;

  /**
   * 이 기기로 푸시를 받는다 (NT-12).
   *
   * <p>같은 주소가 이미 있으면 키와 주인을 갱신한다. <b>주인까지 옮기는 것은 공용 기기 때문이다</b> — 앞사람이 로그아웃하고 뒷사람이 알림을 켜면 같은 주소가 새
   * 주인으로 오는데, 안 옮기면 앞사람의 알림이 뒷사람 기기로 간다.
   */
  @Transactional
  public void register(Long userId, String endpoint, String p256dh, String auth) {
    pushSubscriptionRepository.upsert(
        userId, endpoint, PushSubscription.hash(endpoint), p256dh, auth, nowInUtc());
  }

  /**
   * 이 기기만 끊는다 (NT-12).
   *
   * <p><b>없는 구독을 지워도 성공이다.</b> 화면에서 알림을 끄는 동작이라 몇 번을 눌러도 같은 결과여야 하고, 브라우저가 이미 구독을 버린 뒤에 부르는 것도 정상
   * 경로다.
   */
  @Transactional
  public void unregister(Long userId, String endpoint) {
    pushSubscriptionRepository.deleteByUserIdAndEndpointHash(
        userId, PushSubscription.hash(endpoint));
  }

  /**
   * 탈퇴한 사람의 구독을 전부 지운다 (AU-11 · NT-12).
   *
   * <p><b>탈퇴와 같은 트랜잭션에서 돈다.</b> {@code MANDATORY} 가 그것을 강제한다 — {@code UserWithdrawn} 의 구독 규약 ①이고,
   * 탈퇴가 롤백되면 구독도 남아야 하고 커밋되면 구독은 반드시 없어야 한다.
   *
   * <p><b>안 지우면 탈퇴한 사람 폰에 알림이 뜬다.</b> 탈퇴는 토큰만 끊고 행은 남기는 소프트 삭제라 (진짜 파기는 AD-05) 그 사람의 옛 댓글에 답글이 달리면
   * 알림이 그대로 발행된다. 인앱은 로그인이 막혀 아무도 못 보지만 <b>푸시는 로그인 없이 기기에 직접 닿는다.</b>
   *
   * <p><b>처리방침 제3조는 탈퇴를 트리거로 적지 않았다.</b> 거기 적힌 것은 보관의 상한이고 우리는 더 짧게 지우므로 어긋나지 않는다. 문면 보완은 담당자에게 넘겼다
   * (티켓 본문).
   */
  @EventListener
  @Transactional(propagation = Propagation.MANDATORY)
  public void forgetAll(UserWithdrawn withdrawn) {
    pushSubscriptionRepository.deleteByUserId(withdrawn.userId());
  }

  /**
   * UTC 기준 현재 시각.
   *
   * <p><b>{@code LocalDateTime.now(clock)} 이 아니다.</b> {@code ClockConfig} 의 시계가 {@code Asia/Seoul}
   * 이라 그대로 쓰면 다른 표와 아홉 시간 어긋난다 — 알림 워커와 감사 로그가 같은 자리에서 같은 변환을 쓴다.
   */
  private LocalDateTime nowInUtc() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
