package com.duckmoim.auth.domain;

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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서버가 보관하는 Refresh 토큰 한 장 (AU-02 「Refresh 는 서버 저장」).
 *
 * <p><b>애그리게이트 {@code AuthSession} 의 루트다</b> (도메인-모델링.md 「3.1 경계와 트랜잭션 범위」). 테이블명도 루트를 따라 {@code
 * refresh_token} 이다 — 실제로 담기는 것은 Refresh 해시 한 줄이고 Access 는 저장하지 않으므로, {@code auth_session} 은 담기지 않은
 * 것까지 있는 것처럼 들린다.
 *
 * <p><b>원문을 절대 갖지 않는다.</b> 생성자가 비공개이고 {@link #create} 만 열려 있어서, 원문을 넣으려면 반드시 {@link #hash} 를 지난다.
 * DB 가 유출되면 그 자체로 14일짜리 세션 전부를 넘겨주는 셈이라, 이 불변식을 service 가 아니라 여기서 강제한다.
 *
 * <p>회원을 {@code userId} 로만 참조한다 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」). 한 회원이 여러 기기에서 로그인하므로 행이 여러 개 있을
 * 수 있고, AU-03 의 「해당 유저 전체 폐기」가 그 전부를 지운다.
 */
@Entity
@Table(name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends BaseEntity {

  private static final String ALGORITHM = "SHA-256";

  /**
   * 회전된 토큰을 <b>이중 제출로 봐주는 폭</b> (AU-03).
   *
   * <p>같은 Refresh 가 이 안에 다시 오면 재사용이 아니라 「내 다른 탭이 방금 돌렸다」로 본다. 서버가 둘을 구분할 수 있는 신호는 <b>시간 근접성</b>뿐이다
   * — 이중 제출은 밀리초 간격이고 탈취는 분·시간 뒤다.
   *
   * <p>대가는 이 폭 안에서 벌어진 진짜 탈취의 재사용을 놓치는 것이다. 폭을 넓히면 그 창이 커지고, 좁히면 느린 네트워크의 이중 제출이 전체 로그아웃이 된다.
   */
  public static final Duration ROTATION_GRACE = Duration.ofSeconds(5);

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  /** SHA-256 hex 라 길이가 64 로 고정된다. 그래도 컬럼은 {@code VARCHAR(64)} 다 — V12 주석에 이유가 있다. */
  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  /**
   * <b>읽는 코드가 없다.</b> 만료 판정은 Refresh JWT 의 {@code exp} 가 하고({@code readRefreshToken} 이 거절한다), 소유자
   * 판정은 회전 {@code DELETE} 의 {@code user_id} 조건이 한다. 이 컬럼은 <b>서버가 가진 사망 시각의 기록</b>이고, 만료된 행을 지우는 정리
   * 배치가 쓸 자리다 (계획서 「남겨둔 것」).
   */
  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;

  /**
   * 회전된 시각. {@code null} 이면 아직 살아 있는 토큰이다 (V13).
   *
   * <p><b>회전은 행을 지우지 않고 이 값을 찍는다.</b> 지우면 「방금 회전됐다」와 「오래 전에 죽었다」를 구분할 수 없고, 그 구분이 없으면 이중 제출이 재사용으로
   * 오인된다. 유예를 넘긴 행은 다음 회전이 함께 지운다 — 별도 정리 배치가 없다.
   */
  @Column(name = "rotated_at")
  private LocalDateTime rotatedAt;

  private RefreshToken(Long userId, String tokenHash, LocalDateTime expiresAt) {
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
  }

  /** 원문을 받아 해시만 담는다. 원문은 이 메서드 밖으로 나가지 않는다. */
  public static RefreshToken create(Long userId, String rawToken, LocalDateTime expiresAt) {
    return new RefreshToken(userId, hash(rawToken), expiresAt);
  }

  /**
   * 조회도 해시로 한다.
   *
   * <p>원문으로는 행을 찾을 수 없으므로 service 가 이 메서드로 바꿔서 저장소에 넘긴다. 알고리즘이 두 곳에 있으면 한 곳만 바뀌는 날 조회가 조용히 전부 실패하므로
   * 여기 하나만 둔다.
   */
  public static String hash(String rawToken) {
    try {
      byte[] hashed =
          MessageDigest.getInstance(ALGORITHM).digest(rawToken.getBytes(StandardCharsets.UTF_8));

      return HexFormat.of().formatHex(hashed);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(ALGORITHM + " 를 쓸 수 없는 런타임이다.", e);
    }
  }
}
