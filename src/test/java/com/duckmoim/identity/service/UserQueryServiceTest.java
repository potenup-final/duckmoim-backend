package com.duckmoim.identity.service;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.LastSeen;
import com.duckmoim.identity.domain.SignupStatus;
import com.duckmoim.identity.exception.UserErrorCode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 닉네임 사전 조회와 내 정보 (AU-06 · API 설계 2-2).
 *
 * <p>동시 요청 판정은 여기 없다 — 유니크 제약과 409 변환이 걸려 있어 {@code UserServiceTest} 가 본다.
 */
@SpringBootTest
@Transactional
@DisplayName("회원 조회")
class UserQueryServiceTest {

  @Autowired private UserQueryService userQueryService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("아무도 쓰지 않는 닉네임은 쓸 수 있다고 답한다.")
  void isNicknameAvailable() {
    assertThat(userQueryService.isNicknameAvailable("아직없는이름")).isTrue();
  }

  @Test
  @DisplayName("쓰이고 있는 닉네임은 쓸 수 없다고 답한다.")
  void isNicknameAvailable_taken() {
    aUser().nickname("이미쓰는이름").insert(jdbcTemplate);

    assertThat(userQueryService.isNicknameAvailable("이미쓰는이름")).isFalse();
  }

  @Test
  @DisplayName("내 정보를 읽으면 프로필과 가입 완료 여부가 온다.")
  void findMyProfile() {
    long userId = aUser().nickname("성수러버").insert(jdbcTemplate);

    MyProfile profile = userQueryService.findMyProfile(userId);

    assertThat(profile.id()).isEqualTo(userId);
    assertThat(profile.nickname()).isEqualTo("성수러버");
    assertThat(profile.signupCompleted()).isTrue();
  }

  /** 도메인 7.2 — 본인 조회에서도 원본 시각이 아니라 구간으로 내린다. */
  @Test
  @DisplayName("마지막 접속 시각은 구간 값으로 온다.")
  void findMyProfile_lastSeenIsBucket() {
    long userId =
        aUser().lastSeenAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(2)).insert(jdbcTemplate);

    assertThat(userQueryService.findMyProfile(userId).lastSeen()).isEqualTo(LastSeen.WITHIN_3_DAYS);
  }

  /** 한 번도 관측되지 않았으면 구간도 없다. 방금 가입한 사람을 LONG_AGO 로 적으면 안 된다. */
  @Test
  @DisplayName("접속이 관측된 적 없으면 구간 값이 없다.")
  void findMyProfile_lastSeenNeverObserved() {
    long userId = aUser().insert(jdbcTemplate);

    assertThat(userQueryService.findMyProfile(userId).lastSeen()).isNull();
  }

  /** {@code Sanction} 이 아직 없어서 고정으로 나간다. 키 자체는 계약이라 빼지 않는다 (API 설계 2-2). */
  @Test
  @DisplayName("제재 정보는 제재 없음으로 온다.")
  void findMyProfile_sanctionIsNone() {
    long userId = aUser().insert(jdbcTemplate);

    SanctionView sanction = userQueryService.findMyProfile(userId).sanction();

    assertThat(sanction.kind()).isEqualTo("NONE");
    assertThat(sanction.reason()).isNull();
    assertThat(sanction.until()).isNull();
  }

  @Test
  @DisplayName("가입을 마치지 않은 계정도 내 정보를 읽을 수 있다.")
  void findMyProfile_signupIncomplete() {
    long userId = aUser().status(SignupStatus.PENDING_SIGNUP_INFO).insert(jdbcTemplate);

    MyProfile profile = userQueryService.findMyProfile(userId);

    assertThat(profile.signupCompleted()).isFalse();
    assertThat(profile.nickname()).isNull();
  }

  @Test
  @DisplayName("탈퇴한 계정의 정보는 읽을 수 없다.")
  void findMyProfile_withdrawn() {
    long userId = aUser().status(SignupStatus.WITHDRAWN).insert(jdbcTemplate);

    assertThatThrownBy(() -> userQueryService.findMyProfile(userId))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NOT_FOUND);
  }
}
