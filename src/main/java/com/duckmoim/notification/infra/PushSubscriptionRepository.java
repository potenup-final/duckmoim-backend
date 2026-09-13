package com.duckmoim.notification.infra;

import com.duckmoim.notification.domain.PushSubscription;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 푸시 구독을 읽고 쓴다 (NT-12 · NT-13 · NT-14). */
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

  /**
   * 없으면 넣고 있으면 갱신한다 (NT-12).
   *
   * <p><b>조회하고 나서 쓰지 않는 이유는 경쟁이다.</b> 같은 기기가 동시에 두 번 등록하면 둘 다 「없다」를 받고 둘 다 INSERT 해서, 뒤엣것이 유니크 제약에
   * 걸려 500 이 된다. 그것을 잡으려면 영속성 컨텍스트가 롤백 표시로 죽으므로 빈을 둘로 갈라야 하는데 ({@code ChatMessageSendService} 가 그
   * 모양이다), 여기는 <b>돌려줄 값이 없어</b> 한 문장으로 끝내는 편이 낫다.
   *
   * <p><b>주인을 함께 갱신한다.</b> 공용 기기에서 사람이 바뀌면 같은 주소가 새 주인으로 온다. 안 옮기면 앞사람의 알림이 뒷사람 기기로 간다.
   *
   * <p><b>{@code created_at} 은 갱신 목록에 없다.</b> 「언제부터 이 기기가 구독했나」는 재등록으로 바뀌지 않는다.
   *
   * <p><b>JPA 생명주기를 지나지 않는다.</b> {@code BaseEntity} 의 {@code @PrePersist} 가 안 돌아서 시각을 인자로 받는다 — 그
   * 값은 다른 표와 같은 UTC 다.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO push_subscription
              (user_id, endpoint, endpoint_hash, p256dh, auth, created_at, updated_at)
          VALUES (:userId, :endpoint, :endpointHash, :p256dh, :auth, :nowInUtc, :nowInUtc) AS new
          ON DUPLICATE KEY UPDATE
              user_id = new.user_id,
              p256dh = new.p256dh,
              auth = new.auth,
              updated_at = new.updated_at
          """,
      nativeQuery = true)
  void upsert(
      @Param("userId") Long userId,
      @Param("endpoint") String endpoint,
      @Param("endpointHash") String endpointHash,
      @Param("p256dh") String p256dh,
      @Param("auth") String auth,
      @Param("nowInUtc") LocalDateTime nowInUtc);

  /** 이 사람의 구독 전부. 발송이 부르는 자리다 (NT-13). */
  List<PushSubscription> findByUserId(Long userId);

  /**
   * 이 사람의 구독을 오래된 순으로. 상한을 넘겼을 때 <b>앞에서부터</b> 버리려고 쓴다 (PR #157 리뷰).
   *
   * <p>거절이 아니라 버리는 쪽을 고른 이유는 {@code PushSubscriptionService} 에 있다.
   */
  List<PushSubscription> findByUserIdOrderByIdAsc(Long userId);

  /** 이미 있는 기기인가. 있으면 갱신이라 구독 수가 늘지 않는다. */
  boolean existsByEndpointHash(String endpointHash);

  /**
   * 이 사람의 그 기기 하나를 끊는다 (NT-12).
   *
   * <p><b>주인 조건을 함께 건다.</b> 주소만으로 지우면 남의 기기를 끊을 수 있다 — 주소는 비밀이 아니고, 공용 기기를 쓴 사람은 그 값을 본 적이 있다.
   */
  int deleteByUserIdAndEndpointHash(Long userId, String endpointHash);

  /**
   * 만료된 기기를 지운다 (NT-14).
   *
   * <p><b>주인 조건이 없다.</b> 푸시 서비스가 「그 주소는 이제 없다」고 답한 것이라 누구의 것이든 지워야 한다 — 해제(사용자의 명령)와 갈리는 지점이다.
   *
   * <p>부르는 쪽이 발송 중이라 트랜잭션 밖이다. 지우지 못해도 다음 발송이 같은 답을 받아 다시 지운다.
   */
  int deleteByEndpointHash(String endpointHash);

  /**
   * 그 사람의 구독 전부를 지운다. 탈퇴가 부르는 자리다.
   *
   * <p>{@code idx_push_subscription_user} 가 발송 조회와 함께 이 조건도 받는다 (V808).
   */
  int deleteByUserId(Long userId);
}
