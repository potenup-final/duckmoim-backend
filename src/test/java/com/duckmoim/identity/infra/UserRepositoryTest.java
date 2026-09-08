package com.duckmoim.identity.infra;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.identity.domain.SignupStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 닉네임 사전 조회 (AU-06).
 *
 * <p>실제 MySQL 로 돈다 — 테스트 컨벤션이 H2 를 금지했다. <b>동시 요청 판정은 여기 없다</b> — 유니크 제약과 409 변환이 걸려 있어 service 의
 * 멀티스레드 통합 테스트가 본다. 여기서는 조회가 무엇을 세는지만 못박는다.
 */
@SpringBootTest
@Transactional
class UserRepositoryTest {

  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("쓰이고 있는 닉네임은 있다고 답한다.")
  void existsByNickname() {
    aUser().nickname("성수덕후").insert(jdbcTemplate);

    assertThat(userRepository.existsByNickname("성수덕후")).isTrue();
  }

  @Test
  @DisplayName("아무도 쓰지 않는 닉네임은 없다고 답한다.")
  void existsByNickname_notTaken() {
    aUser().nickname("성수덕후").insert(jdbcTemplate);

    assertThat(userRepository.existsByNickname("잠실덕후")).isFalse();
  }

  /**
   * <b>탈퇴 회원의 닉네임도 쓰이는 중이다.</b>
   *
   * <p>탈퇴는 소프트 삭제라 행이 남고 {@code uk_user_nickname} 이 그 행을 그대로 센다. 여기서만 걸러내면 「사용 가능」이라고 답한 닉네임이 저장에서
   * 409 가 되어 사용자가 원인을 알 수 없다.
   */
  @Test
  @DisplayName("탈퇴한 회원이 쓰던 닉네임도 있다고 답한다.")
  void existsByNickname_withdrawn() {
    aUser().nickname("탈퇴한덕후").status(SignupStatus.WITHDRAWN).insert(jdbcTemplate);

    assertThat(userRepository.existsByNickname("탈퇴한덕후")).isTrue();
  }

  /** 가입 미완료 계정은 닉네임이 없다 (V10 에서 NULL 허용). 그 상태가 조회를 깨뜨리지 않아야 한다. */
  @Test
  @DisplayName("닉네임이 없는 계정이 있어도 조회가 깨지지 않는다.")
  void existsByNickname_pendingUserHasNoNickname() {
    aUser().status(SignupStatus.PENDING_SIGNUP_INFO).insert(jdbcTemplate);

    assertThat(userRepository.existsByNickname("아무거나")).isFalse();
  }
}
