package com.duckmoim.notification.service;

import com.duckmoim.identity.domain.UserWithdrawn;
import com.duckmoim.notification.domain.PushSubscription;
import com.duckmoim.notification.infra.PushSubscriptionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

  /**
   * 한 사람이 가질 수 있는 기기 수 (PR #157 리뷰).
   *
   * <p><b>없으면 증폭 통로가 된다.</b> 구독 하나가 알림 하나마다 HTTP 요청 하나이므로, 수천 개를 등록해 두면 그 사람에게 가는 알림 한 건이 수천 번의 발송이
   * 되어 배치가 거기서 멈춘다.
   *
   * <p>열로 둔 것은 실제로 쓰는 기기가 폰·태블릿·노트북·회사 PC 정도이고, 브라우저를 갈아입을 때마다 새 주소가 나오는 것을 감안한 값이다.
   */
  private static final int MAX_DEVICES = 10;

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
    // 주소 검증과 해시 계산이 여기를 지난다. 저장은 upsert 한 문장이 하지만, 그 둘이 갈리면
    // 재등록이 매번 새 행을 만든다.
    PushSubscription candidate = PushSubscription.of(userId, endpoint, p256dh, auth);

    evictOldestIfFull(userId, candidate.getEndpointHash());

    pushSubscriptionRepository.upsert(
        userId,
        candidate.getEndpoint(),
        candidate.getEndpointHash(),
        candidate.getP256dh(),
        candidate.getAuth(),
        nowInUtc());
  }

  /**
   * 기기 수가 상한을 넘으면 오래된 것부터 버린다 (PR #157 리뷰).
   *
   * <p><b>거절하지 않고 버린다.</b> 거절하면 기기를 오래 쓴 사람이 어느 날 알림을 못 켜게 되는데, 그 사람은 무엇이 문제인지 알 길이 없다. 오래된 주소는 대개
   * 이미 죽은 것이고 (브라우저를 갈아입으면 새 주소가 나온다) 죽은 것은 발송 때 410 으로 정리되지만, 그 정리는 <b>알림이 갈 일이 있어야</b> 돈다.
   *
   * <p><b>이미 있는 기기면 아무 일도 하지 않는다.</b> 그때 등록은 갱신이라 수가 늘지 않는다 — 세지 않으면 재등록만 반복해도 멀쩡한 기기가 밀려난다.
   */
  private void evictOldestIfFull(Long userId, String endpointHash) {
    if (pushSubscriptionRepository.existsByEndpointHash(endpointHash)) {
      return;
    }

    List<PushSubscription> mine = pushSubscriptionRepository.findByUserIdOrderByIdAsc(userId);
    int over = mine.size() + 1 - MAX_DEVICES;

    if (over > 0) {
      pushSubscriptionRepository.deleteAll(mine.subList(0, over));
    }
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
