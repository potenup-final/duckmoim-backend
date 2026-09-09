package com.duckmoim.identity.domain;

import com.duckmoim.common.domain.BaseEntity;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.exception.UserErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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

  @Embedded private BirthYear birthYear;

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

  /**
   * 카카오 회원번호만으로 계정을 만든다 — 「최초 로그인 시 자동 가입」 (AU-01).
   *
   * <p><b>여기서 태어난 계정은 아직 아무것도 쓸 수 없다.</b> {@code PENDING_SIGNUP_INFO} 로 시작하고 닉네임 · 출생연도가 비어 있다.
   * 요구사항이 <i>"최초 로그인 시 자동 가입, 가입 정보 미입력 상태로 진입"</i> 이라고 정한 상태가 이것이고, {@link #completeSignup} 이 그 다음
   * 칸을 채운다.
   *
   * <p><b>카카오에서 받는 것은 회원번호뿐이다</b> (결정 D-2). 닉네임을 카카오 것으로 채우지 않는다 — 채우면 I-01(닉네임 유일)이 사용자가 고를 기회도 없이
   * 남의 닉네임과 부딪히고, 동의항목에서 프로필을 빼는 선택지도 사라진다.
   */
  public static User signUp(Long kakaoUserId) {
    User user = new User();
    user.kakaoUserId = kakaoUserId;
    user.status = SignupStatus.PENDING_SIGNUP_INFO;
    return user;
  }

  /**
   * 가입 정보를 채워 활동할 수 있는 계정으로 만든다 (AU-05).
   *
   * <p>가입 축의 유일한 전진 전이다 — {@code PENDING_SIGNUP_INFO ──입력──▶ ACTIVE} (도메인 6장).
   *
   * <p><b>한 번만 통한다.</b> API 설계 2-2 가 <i>"이미 입력한 유저가 다시 부르면 409 다 — 출생연도는 가입 후 잠기기 때문이다"</i> 로 정했다.
   * 닉네임만 바꾸는 것은 AU-08 의 {@code PATCH /users/me/profile} 몫이다.
   *
   * <p>탈퇴 계정도 이 검사에 걸린다. 다만 그쪽은 관문이 먼저 끊으므로 여기까지 오지 않는다 ({@link #isWithdrawn}).
   *
   * @throws BusinessException {@code PENDING_SIGNUP_INFO} 가 아니면 {@code
   *     USER_SIGNUP_INFO_ALREADY_SET}
   */
  public void completeSignup(SignupInfo signupInfo) {
    if (!isSignupPending()) {
      throw new BusinessException(UserErrorCode.USER_SIGNUP_INFO_ALREADY_SET);
    }

    this.nickname = signupInfo.nickname();
    this.birthYear = signupInfo.birthYear();
    this.status = SignupStatus.ACTIVE;
  }

  /**
   * 프로필을 고친다 (AU-08).
   *
   * <p><b>부분 수정이다.</b> {@code null} 인 필드는 건드리지 않는다 — {@code PATCH} 라 「보내지 않았다」와 「비워 달라」가 달라야 하고, 그
   * 구분을 {@link Profile} 이 값으로 표현한다.
   *
   * <p><b>빈 한줄소개는 {@code null} 로 저장한다.</b> 「비어 있다」를 표현하는 방법이 두 가지가 되면 조회하는 쪽이 둘 다 검사해야 한다 — 자동 가입
   * 직후는 {@code null} 이고 지운 뒤는 {@code ""} 가 되어, 같은 화면 상태가 값 둘로 갈린다.
   *
   * <p><b>닉네임은 비울 수 없다.</b> 빈 값이 오면 여기까지 도달하지 않는다 — 요청 DTO 가 400 으로 끊는다. 도메인이 다시 막지 않는 이유는 「비움」의
   * 의미가 프로필 수정이라는 <b>입력 계약</b>에서 나오기 때문이다. 유일성(I-01)은 여기가 아니라 DB 제약이 지킨다.
   *
   * <p><b>출생연도를 바꾸지 않는다.</b> 가입 후 잠기고({@link #completeSignup}) {@link Profile} 에 필드조차 없다.
   */
  public void updateProfile(Profile profile) {
    if (profile.nickname() != null) {
      this.nickname = profile.nickname();
    }
    if (profile.bio() != null) {
      this.bio = profile.bio().isBlank() ? null : profile.bio();
    }
  }

  /**
   * 계정을 탈퇴 처리한다 (AU-11).
   *
   * <p>가입 축의 마지막 전이다 — {@code ACTIVE ──탈퇴──▶ WITHDRAWN} (도메인 6장). 되돌리는 전이가 없다.
   *
   * <p><b>소프트 삭제다.</b> 행을 지우지 않는다 — 탈퇴한 사람이 쓴 댓글·모집글이 목록에 남아야 하고(AU-11 「작성 댓글은 자리표시자 유지」 · CM-11 의
   * 고아 방지와 같은 이유), 작성자 블록이 {@code user} 행을 내부 조인으로 읽으므로 행이 사라지면 그 댓글들이 목록에서 통째로 빠진다.
   *
   * <p><b>{@code status} 와 {@code withdrawnAt} 을 함께 찍는다.</b> 판정의 정본은 {@code status} 이고({@link
   * #isWithdrawn}) {@code withdrawnAt} 은 <b>언제였는지</b>의 기록이다. 하나만 찍으면 그 기록이 사라진다.
   *
   * <p><b>익명화가 닉네임 하나로 끝나지 않는다.</b> 작성자 블록에 나가는 값이 <b>닉네임과 프로필 이미지 둘</b>이다 — 이름만 지우고 사진을 남기면 익명화의
   * 목적이 성립하지 않는다. 사진이 이름보다 더 식별적이다.
   *
   * <p><b>닉네임을 고정 문자열로 바꾸지 않고 비운다.</b> {@code uk_user_nickname} 이 UNIQUE 라 {@code "탈퇴한 회원"} 같은 값을
   * 넣으면 <b>두 번째 탈퇴자에서 제약 위반</b>이다. MySQL 유니크는 NULL 중복을 허용한다. 그리고 익명 문구는 <b>화면 문구</b>다 — DB 에 박으면
   * 문구가 바뀔 때 마이그레이션이 필요해진다 (결정 D-2 가 기본 아바타에 대해 내린 판단과 같다).
   *
   * <p><b>비우면 그 닉네임이 풀린다.</b> 남이 다시 쓸 수 있다 — 유니크 제약이 NULL 을 세지 않기 때문이고, 탈퇴한 사람이 이름을 영구히 점유하지 않는 것이
   * 맞다.
   *
   * <p>{@code bio} 와 {@code birthYear} 는 건드리지 않는다. 탈퇴 후 어느 응답에도 나가지 않아서다 — 파기 범위는 처리방침이 정할 일이라 이
   * 메서드에서 넓히지 않는다.
   *
   * @throws BusinessException 활동할 수 있는 계정이 아니면 {@code USER_NOT_FOUND}
   */
  public void withdraw(LocalDateTime now) {
    if (!isSignupCompleted()) {
      throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
    }

    this.status = SignupStatus.WITHDRAWN;
    this.withdrawnAt = now;
    this.nickname = null;
    this.profileImageUrl = null;
  }

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

  /**
   * 가입 정보를 아직 안 낸 계정인지.
   *
   * <p><b>{@link #completeSignup} 의 통과 조건 그 자체다.</b> service 가 그 호출에 <b>앞서</b> 같은 판정을 해야 해서 꺼냈다 —
   * 닉네임 사전 조회보다 이 검사가 먼저여야 하고(둘 다 걸리면 상태 쪽이 이긴다), 사전 조회는 엔티티를 바꾸기 전에 끝나야 한다.
   *
   * <p><b>{@code isSignupCompleted} 의 반대가 아니다.</b> 탈퇴한 계정은 둘 다 {@code false} 다. 그래서 이 판정을 부정으로 대신
   * 쓰면 상태가 하나 늘어나는 날 조용히 갈라진다.
   */
  public boolean isSignupPending() {
    return status == SignupStatus.PENDING_SIGNUP_INFO;
  }
}
