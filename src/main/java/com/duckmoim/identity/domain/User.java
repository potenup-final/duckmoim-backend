package com.duckmoim.identity.domain;

import com.duckmoim.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Comment · Safety · Admin 을 먼저 개발하려고 세운 최소 형태다. 상태 전이 메서드가 없고 읽기만 된다.
 *
 * <p>댓글 응답의 작성자 블록(API 2-5), 비밀 댓글 열람 판정(도메인 7.1), 제재 대상, 관리자 인가(D-5) 가 전부 이 엔티티를 읽는다.
 *
 * <p>가입 · 프로필 · 탈퇴의 상태 전이는 Identity 담당이 채운다. 컬럼은 도메인 3.1 의 애그리게이트 경계대로 이미 다 있어 스키마를 다시 건드릴 일은 없다.
 */
@Entity
@Table(name = "user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "kakao_user_id", nullable = false, unique = true)
  private Long kakaoUserId;

  @Column(name = "nickname", length = 20, unique = true)
  private String nickname;

  @Column(name = "birth_year")
  private Integer birthYear;

  /**
   * 한줄소개.
   *
   * <p><b>컬럼과 필드가 {@code intro} 였다.</b> API 설계 2-2 가 응답 필드를 {@code bio} 로 못박았고(<i>"한줄소개 필드명은 bio
   * 다"</i>) API 컨벤션이 도메인 식별자와 API 필드명을 일치시키라고 해서 V14 가 컬럼을 옮겼다.
   */
  @Column(name = "bio", length = 100)
  private String bio;

  @Column(name = "profile_image_url", length = 500)
  private String profileImageUrl;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private SignupStatus status;

  // 저장 전용이다. 어느 응답에도 그대로 나가지 않고 lastSeen 구간으로만 나간다 (도메인 7.2).
  @Column(name = "last_seen_at")
  private LocalDateTime lastSeenAt;

  @Column(name = "withdrawn_at")
  private LocalDateTime withdrawnAt;

  /**
   * 이 시각 이전에 발급된 토큰을 전부 죽인다 (AU-04 「Access 잔여 TTL 차단」).
   *
   * <p><b>{@code refresh_token} 행을 지우는 것만으로는 부족하다.</b> 이미 발급된 Access 는 서명만으로 검증되므로 최대 30분 더 산다. 그
   * 구멍을 여기서 막는다. 이 컬럼을 {@code refresh_token} 에 둘 수 없는 이유도 같다 — 그 행을 전부 지운 뒤에는 비교할 행이 남지 않는다.
   */
  @Column(name = "tokens_invalidated_at")
  private LocalDateTime tokensInvalidatedAt;

  /** AU-03 재사용 탐지의 「해당 유저 전체 폐기」와 AU-04 로그아웃이 함께 부른다. */
  public void invalidateAllTokens(LocalDateTime now) {
    this.tokensInvalidatedAt = now;
  }

  /** AU-03 「재발급 성공 시 lastSeenAt 갱신」. */
  public void updateLastSeenAt(LocalDateTime now) {
    this.lastSeenAt = now;
  }

  /**
   * 이 발급 시각의 토큰이 무효화됐는지 본다. 인증 필터가 매 요청에서 부른다.
   *
   * <p><b>더 이른 발급만 죽인다({@code isBefore}).</b> JWT 의 {@code iat} 은 초 단위로 내려가고 무효화 시각은 마이크로초까지 남으므로,
   * 같은 초에 로그아웃하면 그 초에 발급된 토큰도 걸린다. 여는 쪽에 두면 로그아웃 직전에 발급된 토큰이 30분을 더 살아서, 그쪽이 훨씬 나쁘다.
   *
   * <p><b>대가를 정확히 적는다 — 무효화와 같은 초에 발급된 토큰은 그 토큰의 수명 30분 내내 거절된다.</b> {@code iat} 이 이미 고정된 값이라 시간이
   * 흐른다고 풀리지 않는다. 「다음 초에 풀린다」는 <b>그때 새로 발급받는 토큰</b> 이야기이고, 이미 손에 든 토큰은 버려야 한다. 클라이언트는 {@code
   * AUTH_ACCESS_TOKEN_INVALID} 를 받으므로 재로그인으로 빠져나온다.
   *
   * <p>그래서 <b>무효화를 반복해서 찍으면 안 된다</b> — 매초 앞으로 밀리면 재로그인해서 받은 토큰도 매번 걸려 빠져나올 방법이 없어진다. {@code
   * AuthService} 가 재사용 탐지에서 멱등하게 처리하는 이유다.
   */
  public boolean isTokenInvalidated(LocalDateTime issuedAt) {
    return tokensInvalidatedAt != null && issuedAt.isBefore(tokensInvalidatedAt);
  }

  /**
   * 그 발급 시각의 토큰이 <b>지난 무효화에 이미 덮였는지</b> 본다 (AU-03 재사용 탐지의 멱등 판정).
   *
   * <p><b>{@link #isTokenInvalidated} 와 초 단위 처리가 반대다.</b> 저쪽은 「이 요청을 통과시킬까」라 경계를 닫는 쪽에 두고, 이쪽은 「폐기를
   * 한 번 더 실행할까」라 <b>여는 쪽</b>에 둔다. 무효화 시각을 초로 내려 비교하므로, 무효화와 <b>같은 초에 발급된</b> 토큰은 「덮이지 않았다」로 본다 — 그
   * 토큰은 무효화 직후에 발급된 것일 수 있어서 재사용이면 탐지해야 한다.
   *
   * <p>더 이른 초에 발급된 토큰은 그 무효화가 이미 행을 지우고 Access 를 끊었으므로, 다시 폐기해도 새로 끊을 것이 없다. 그때 또 찍으면 무효화 시각이 앞으로
   * 밀려 <b>그 사이 재로그인한 사용자까지 계속 끊긴다.</b>
   */
  public boolean isCoveredByPastInvalidation(LocalDateTime issuedAt) {
    return tokensInvalidatedAt != null
        && issuedAt.isBefore(tokensInvalidatedAt.truncatedTo(ChronoUnit.SECONDS));
  }

  /**
   * 탈퇴한 계정인지.
   *
   * <p><b>탈퇴는 소프트 삭제다</b> — {@code withdrawn_at} 을 찍고 행은 남긴다 (V10). 탈퇴한 사람이 쓴 댓글·모집글이 「탈퇴한 회원」으로
   * 표시돼야 하므로 행을 지울 수 없다. 그래서 <b>행이 있는지로는 탈퇴를 판별할 수 없다.</b>
   *
   * <p>판정을 {@code status} 로 한다. {@code withdrawn_at} 은 시각의 기록이고, 「가입 축의 상태」는 이 값이 정본이다 (도메인 6장).
   * {@link #isSignupCompleted} 도 같은 필드를 본다 — 둘이 다른 필드를 보면 한쪽만 갱신됐을 때 갈라진다.
   */
  public boolean isWithdrawn() {
    return status == SignupStatus.WITHDRAWN;
  }

  /**
   * 가입 정보를 마쳤는지 (AU-05).
   *
   * <p>재발급이 새 Access 토큰에 이 값을 다시 찍는다. Refresh 토큰에 담아 두면 가입을 마친 사용자가 14일 동안 낡은 {@code false} 를
   * 물려받는다.
   */
  public boolean isSignupCompleted() {
    return status == SignupStatus.ACTIVE;
  }
}
