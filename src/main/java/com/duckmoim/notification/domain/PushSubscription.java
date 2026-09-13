package com.duckmoim.notification.domain;

import com.duckmoim.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 한 기기의 푸시 구독 (NT-12).
 *
 * <p><b>주소가 곧 기기다.</b> 푸시 서비스가 기기마다 발급하고, 한 유저가 기기 수만큼 가진다 — 검증 기준이 「기기 둘에서 등록하면 둘 다 받는다」다.
 *
 * <p><b>{@code common} 이 아니라 여기 산다.</b> {@code NotificationOutbox} 는 Companion 과 Chat 이 넣어서 {@code
 * common} 에 있지만, 구독은 넣는 쪽도 읽는 쪽도 Notification 하나다. 의존이 거꾸로 흐를 자리가 없다.
 *
 * <p><b>{@link #p256dh} 와 {@link #auth} 는 식별값이 아니라 열쇠다.</b> 알림 본문을 그 기기만 열 수 있게 암호화하는 데 쓴다. 로그에 남기지
 * 않는다.
 */
@Entity
@Table(name = "push_subscription")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushSubscription extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 구독의 주인. 재등록이 이 값을 옮길 수 있다 — 공용 기기에서 사람이 바뀌는 경우다. */
  @Column(name = "user_id", nullable = false)
  private Long userId;

  /** 푸시 서비스가 발급한 주소. */
  @Column(name = "endpoint", nullable = false, updatable = false, length = 1024)
  private String endpoint;

  /**
   * 주소의 SHA-256(hex). <b>유니크 제약과 조회가 이 값에 걸린다</b> (V808).
   *
   * <p>주소 자체에 걸 수 없다 — {@code VARCHAR(1024)} 가 utf8mb4 로 4096바이트라 InnoDB 의 인덱스 키 상한 3072 를 넘는다.
   *
   * <p><b>DB 의 생성 컬럼으로 두지 않았다.</b> 그러면 조회 조건이 {@code SHA2()} 를 써야 해서 JPQL 로 표현되지 않는다.
   */
  @Column(name = "endpoint_hash", nullable = false, updatable = false, length = 64)
  private String endpointHash;

  @Column(name = "p256dh", nullable = false, length = 255)
  private String p256dh;

  @Column(name = "auth", nullable = false, length = 255)
  private String auth;

  private PushSubscription(Long userId, String endpoint, String p256dh, String auth) {
    this.userId = userId;
    this.endpoint = endpoint;
    this.endpointHash = hash(endpoint);
    this.p256dh = p256dh;
    this.auth = auth;
  }

  /** 브라우저가 준 구독 하나를 담는다. */
  public static PushSubscription of(Long userId, String endpoint, String p256dh, String auth) {
    Objects.requireNonNull(userId, "구독은 주인을 가진다.");
    Objects.requireNonNull(endpoint, "구독은 보낼 주소를 가진다.");
    Objects.requireNonNull(p256dh, "구독은 암호화 키를 가진다.");
    Objects.requireNonNull(auth, "구독은 인증 비밀을 가진다.");

    return new PushSubscription(userId, endpoint, p256dh, auth);
  }

  /**
   * 주소를 저장·조회에 쓸 해시로 바꾼다.
   *
   * <p><b>도메인이 쥐는 이유는 한 벌이어야 하기 때문이다.</b> 저장할 때와 찾을 때가 같은 방식으로 계산되지 않으면 <b>재등록이 매번 새 행을 만든다</b> —
   * 그러면 한 기기에 같은 알림이 여러 번 간다.
   *
   * <p>{@code MessageDigest} 는 JDK 라 domain 의 프레임워크 자유 규칙에 걸리지 않는다.
   */
  public static String hash(String endpoint) {
    Objects.requireNonNull(endpoint, "해시할 주소가 필요하다.");

    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(endpoint.getBytes(StandardCharsets.UTF_8));

      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 은 모든 JDK 구현이 제공한다. 여기 오면 실행 환경이 깨진 것이다.
      throw new IllegalStateException("SHA-256 을 쓸 수 없다.", e);
    }
  }
}
