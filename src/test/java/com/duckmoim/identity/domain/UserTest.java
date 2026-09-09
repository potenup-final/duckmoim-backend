package com.duckmoim.identity.domain;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.exception.UserErrorCode;
import com.duckmoim.identity.infra.UserRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 토큰 무효화와 가입 완료 판정 (AU-03 · AU-04).
 *
 * <p><b>대부분이 단위 테스트가 아니다.</b> {@code User.signUp} 은 자동 가입 상태만 만들 수 있어서(AU-01) 「가입을 마친 회원」이나 「무효화
 * 시각이 찍힌 회원」을 세울 수 없다. 그런 상태는 행을 SQL 로 넣고 읽는다 — {@link com.duckmoim.identity.UserFixture} 에 이유가 있다.
 *
 * <p>{@code signUp} 자신을 보는 테스트는 DB 를 쓰지 않는다.
 */
@SpringBootTest
@Transactional
class UserTest {

  private static final LocalDateTime LOGOUT = LocalDateTime.of(2026, 9, 7, 12, 0, 0);
  private static final LocalDateTime WITHDRAWN_AT = LocalDateTime.of(2026, 9, 9, 3, 0, 0);

  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  /**
   * AU-01 「최초 로그인 시 자동 가입, 가입 정보 미입력 상태로 진입」.
   *
   * <p>카카오에서 받는 것은 회원번호뿐이라(결정 D-2) 나머지 칸은 비어 있어야 한다. 닉네임이 채워져 나오면 사용자가 고를 기회도 없이 I-01(닉네임 유일)과
   * 부딪힌다.
   */
  @Test
  @DisplayName("자동 가입한 계정은 가입 정보가 비어 있고 아직 활동할 수 없다.")
  void signUp() {
    User user = User.signUp(4321L);

    assertThat(user.getKakaoUserId()).isEqualTo(4321L);
    assertThat(user.isSignupPending()).isTrue();
    assertThat(user.isSignupCompleted()).isFalse();
    assertThat(user.getNickname()).isNull();
    assertThat(user.getBirthYear()).isNull();
  }

  /** 자동 가입 직후는 「입력 전」이라 {@code completeSignup} 이 통해야 한다. 금지 전이는 아래 두 테스트가 본다. */
  @Test
  @DisplayName("자동 가입한 계정은 가입 정보를 입력할 수 있다.")
  void signUp_thenCompleteSignup() {
    User user = User.signUp(4322L);

    user.completeSignup(SignupInfo.of("성수팝업러", 2000, 2026));

    assertThat(user.isSignupCompleted()).isTrue();
    assertThat(user.getNickname()).isEqualTo("성수팝업러");
  }

  /**
   * AU-08 의 부분 수정 규칙이다. <b>세 상태가 갈린다</b> — {@code null} 은 안 건드림, 빈 문자열은 비움, 그 밖은 교체.
   *
   * <p>DB 를 쓰지 않는다. 값 객체를 받아 필드를 바꾸는 것이 전부라 단위로 충분하다.
   */
  @Test
  @DisplayName("프로필을 고치면 닉네임과 한줄소개가 그 값으로 바뀐다.")
  void updateProfile() {
    User user =
        load(aUser().nickname("고치기전덕후").profile("전 소개", "/avatar/old.webp").insert(jdbcTemplate));

    user.updateProfile(new Profile("고친덕후", "새 소개"));

    assertThat(user.getNickname()).isEqualTo("고친덕후");
    assertThat(user.getBio()).isEqualTo("새 소개");
  }

  /** 프로필 화면이 한줄소개만 고쳐 보내는 것이 가장 흔한 요청이다. 그때 닉네임이 지워지면 안 된다. */
  @Test
  @DisplayName("보내지 않은 필드는 바뀌지 않는다.")
  void updateProfile_partial() {
    User user = load(aUser().nickname("그대로덕후").profile("그대로 소개", null).insert(jdbcTemplate));

    user.updateProfile(new Profile(null, "새 소개만"));

    assertThat(user.getNickname()).isEqualTo("그대로덕후");
    assertThat(user.getBio()).isEqualTo("새 소개만");
  }

  /** 「비어 있다」를 표현하는 값이 둘이 되면 조회하는 쪽이 둘 다 검사해야 한다. */
  @Test
  @DisplayName("빈 한줄소개를 보내면 값이 비워진다.")
  void updateProfile_clearsBio() {
    User user = load(aUser().profile("지울 소개", null).insert(jdbcTemplate));

    user.updateProfile(new Profile(null, ""));

    assertThat(user.getBio()).isNull();
  }

  /** 출생연도는 가입 후 잠긴다 (AU-08). {@code Profile} 에 필드가 없어서 바꿀 수단 자체가 없다. */
  @Test
  @DisplayName("프로필을 고쳐도 출생연도는 그대로다.")
  void updateProfile_keepsBirthYear() {
    User user = load(aUser().insert(jdbcTemplate));
    int before = user.getBirthYear().getValue();

    user.updateProfile(new Profile("연도안바뀜덕후", "소개"));

    assertThat(user.getBirthYear().getValue()).isEqualTo(before);
  }

  /**
   * AU-11 의 상태 전이다 — {@code ACTIVE ──탈퇴──▶ WITHDRAWN} (도메인 6장).
   *
   * <p><b>판정의 정본은 {@code status} 이고 {@code withdrawnAt} 은 언제였는지의 기록이다.</b> 하나만 찍으면 그 기록이 사라진다.
   */
  @Test
  @DisplayName("탈퇴하면 상태가 바뀌고 탈퇴 시각이 남는다.")
  void withdraw() {
    User user = load(aUser().insert(jdbcTemplate));

    user.withdraw(WITHDRAWN_AT);

    assertThat(user.isWithdrawn()).isTrue();
    assertThat(user.isSignupCompleted()).isFalse();
    assertThat(user.getWithdrawnAt()).isEqualTo(WITHDRAWN_AT);
  }

  /**
   * <b>익명화가 닉네임 하나로 끝나지 않는다.</b> 작성자 블록에 나가는 값이 닉네임과 프로필 이미지 둘이라, 이름만 지우고 사진을 남기면 익명화의 목적이 성립하지
   * 않는다.
   */
  @Test
  @DisplayName("탈퇴하면 닉네임과 프로필 이미지가 비워진다.")
  void withdraw_anonymizes() {
    User user =
        load(aUser().nickname("떠나는덕후").profile("소개", "/avatar/mine.webp").insert(jdbcTemplate));

    user.withdraw(WITHDRAWN_AT);

    assertThat(user.getNickname()).isNull();
    assertThat(user.getProfileImageUrl()).isNull();
  }

  /** 파기 범위는 처리방침이 정할 일이라 이 전이에서 넓히지 않는다. 둘 다 탈퇴 후 어느 응답에도 나가지 않는다. */
  @Test
  @DisplayName("탈퇴해도 한줄소개와 출생연도는 남는다.")
  void withdraw_keepsBioAndBirthYear() {
    User user = load(aUser().profile("남는 소개", null).insert(jdbcTemplate));

    user.withdraw(WITHDRAWN_AT);

    assertThat(user.getBio()).isEqualTo("남는 소개");
    assertThat(user.getBirthYear()).isNotNull();
  }

  /** 되돌리는 전이가 없다 (도메인 6장). 두 번째 탈퇴는 「없는 계정」이다. */
  @Test
  @DisplayName("이미 탈퇴한 계정은 다시 탈퇴할 수 없다.")
  void withdraw_alreadyWithdrawn() {
    User user = load(aUser().status(SignupStatus.WITHDRAWN).insert(jdbcTemplate));

    assertThatThrownBy(() -> user.withdraw(WITHDRAWN_AT))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NOT_FOUND);
  }

  /** 가입 미완료 계정은 관문이 403 으로 끊어 여기 도달하지 않는다. 도메인이 다시 막는 것은 방어의 두 번째 겹이다. */
  @Test
  @DisplayName("가입을 마치지 않은 계정은 탈퇴할 수 없다.")
  void withdraw_signupIncomplete() {
    User user = load(aUser().status(SignupStatus.PENDING_SIGNUP_INFO).insert(jdbcTemplate));

    assertThatThrownBy(() -> user.withdraw(WITHDRAWN_AT))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NOT_FOUND);
  }

  @Test
  @DisplayName("무효화 시각이 없으면 어느 시각에 발급된 토큰도 살아 있다.")
  void isTokenInvalidated_neverInvalidated() {
    User user = load(aUser().insert(jdbcTemplate));

    assertThat(user.isTokenInvalidated(LOGOUT.minusYears(1))).isFalse();
  }

  @Test
  @DisplayName("무효화 시각보다 먼저 발급된 토큰은 죽는다.")
  void isTokenInvalidated_authTokenBefore() {
    User user = load(aUser().tokensInvalidatedAt(LOGOUT).insert(jdbcTemplate));

    assertThat(user.isTokenInvalidated(LOGOUT.minusSeconds(1))).isTrue();
  }

  /** 로그아웃 뒤에 다시 로그인해 받은 토큰이다. 이것까지 죽이면 로그인이 아예 안 된다. */
  @Test
  @DisplayName("무효화 시각보다 나중에 발급된 토큰은 살아 있다.")
  void isTokenInvalidated_authTokenAfter() {
    User user = load(aUser().tokensInvalidatedAt(LOGOUT).insert(jdbcTemplate));

    assertThat(user.isTokenInvalidated(LOGOUT.plusSeconds(1))).isFalse();
  }

  /**
   * JWT 의 {@code iat} 은 초 단위로 내려간다. 12:00:00.400 에 발급하고 12:00:00.700 에 로그아웃하면 발급 시각이 12:00:00.000
   * 으로 들어와서, 경계가 열려 있으면 그 토큰이 30분을 더 산다.
   */
  @Test
  @DisplayName("로그아웃과 같은 초에 발급된 토큰도 죽는다.")
  void isTokenInvalidated_authTokenInTheSameSecond() {
    User user =
        load(aUser().tokensInvalidatedAt(LOGOUT.withNano(700_000_000)).insert(jdbcTemplate));

    assertThat(user.isTokenInvalidated(LOGOUT)).isTrue();
  }

  @Test
  @DisplayName("토큰을 무효화하면 그 시각이 남는다.")
  void invalidateAllTokens() {
    User user = load(aUser().insert(jdbcTemplate));

    user.invalidateAllTokens(LOGOUT);

    assertThat(user.isTokenInvalidated(LOGOUT.minusSeconds(1))).isTrue();
  }

  @Test
  @DisplayName("마지막 접속 시각을 갱신하면 그 시각이 남는다.")
  void updateLastSeenAt() {
    User user = load(aUser().lastSeenAt(LOGOUT.minusDays(30)).insert(jdbcTemplate));

    user.updateLastSeenAt(LOGOUT);

    assertThat(user.getLastSeenAt()).isEqualTo(LOGOUT);
  }

  /**
   * 재사용 탐지의 멱등 판정 (AU-03).
   *
   * <p>{@link User#isTokenInvalidated} 와 <b>초 단위 처리가 반대다</b> — 저쪽은 요청을 통과시킬지라 닫는 쪽, 이쪽은 폐기를 한 번 더
   * 실행할지라 여는 쪽이다. 같은 초에 발급된 토큰을 「덮였다」로 보면 폐기 직후에 발급된 토큰의 재사용을 놓친다.
   */
  @Test
  @DisplayName("무효화보다 이른 초에 발급된 토큰은 지난 무효화에 이미 덮였다.")
  void isCoveredByPastInvalidation_earlierSecond() {
    User user =
        load(aUser().tokensInvalidatedAt(LOGOUT.withNano(700_000_000)).insert(jdbcTemplate));

    assertThat(user.isCoveredByPastInvalidation(LOGOUT.minusSeconds(1))).isTrue();
  }

  @Test
  @DisplayName("무효화와 같은 초에 발급된 토큰은 덮인 것으로 보지 않는다.")
  void isCoveredByPastInvalidation_sameSecond() {
    User user =
        load(aUser().tokensInvalidatedAt(LOGOUT.withNano(700_000_000)).insert(jdbcTemplate));

    assertThat(user.isCoveredByPastInvalidation(LOGOUT)).isFalse();
  }

  @Test
  @DisplayName("무효화한 적이 없으면 어느 토큰도 덮이지 않았다.")
  void isCoveredByPastInvalidation_neverInvalidated() {
    User user = load(aUser().insert(jdbcTemplate));

    assertThat(user.isCoveredByPastInvalidation(LOGOUT.minusYears(1))).isFalse();
  }

  @ParameterizedTest
  @CsvSource({"ACTIVE, true", "PENDING_SIGNUP_INFO, false", "WITHDRAWN, false"})
  @DisplayName("가입 완료는 활성 상태에서만 참이다.")
  void isSignupCompleted(SignupStatus status, boolean expected) {
    User user = load(aUser().status(status).insert(jdbcTemplate));

    assertThat(user.isSignupCompleted()).isEqualTo(expected);
  }

  /**
   * 가입 축의 유일한 전진 전이 (AU-05 · 도메인 6장).
   *
   * <p>AU-05 의 검증 기준 <b>「저장 후 재조회 시 동일 값」</b>이라 실제로 다시 읽어 확인한다.
   */
  @Test
  @DisplayName("가입 정보를 입력하면 활성 상태가 되고 값이 그대로 남는다.")
  void completeSignup() {
    long userId = aUser().status(SignupStatus.PENDING_SIGNUP_INFO).insert(jdbcTemplate);
    User user = load(userId);

    user.completeSignup(SignupInfo.of("성수덕후", 2000, 2026));
    userRepository.flush();

    User reloaded = load(userId);
    assertThat(reloaded.getNickname()).isEqualTo("성수덕후");
    assertThat(reloaded.getBirthYear().getValue()).isEqualTo(2000);
    assertThat(reloaded.isSignupCompleted()).isTrue();
  }

  /**
   * 금지된 전이다 — API 설계 2-2 가 <i>"이미 입력한 유저가 다시 부르면 409"</i> 로 정했다.
   *
   * <p><b>출생연도가 가입 후 잠기기 때문이다.</b> 닉네임만 바꾸는 길은 AU-08 이 따로 낸다.
   */
  @Test
  @DisplayName("가입을 마친 계정이 가입 정보를 다시 입력하면 거부된다.")
  void completeSignup_alreadyActive() {
    User user = load(aUser().status(SignupStatus.ACTIVE).insert(jdbcTemplate));

    assertThatThrownBy(() -> user.completeSignup(SignupInfo.of("다른덕후", 2000, 2026)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_SIGNUP_INFO_ALREADY_SET);
  }

  /** 탈퇴 계정도 금지된 전이다. 관문이 먼저 끊지만 도메인도 스스로 막는다. */
  @Test
  @DisplayName("탈퇴한 계정이 가입 정보를 입력하면 거부된다.")
  void completeSignup_withdrawn() {
    User user = load(aUser().status(SignupStatus.WITHDRAWN).insert(jdbcTemplate));

    assertThatThrownBy(() -> user.completeSignup(SignupInfo.of("돌아온덕후", 2000, 2026)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_SIGNUP_INFO_ALREADY_SET);
  }

  private User load(long userId) {
    return userRepository.findById(userId).orElseThrow();
  }
}
