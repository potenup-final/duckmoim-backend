package com.duckmoim.identity;

import com.duckmoim.identity.domain.SignupStatus;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 회원 행을 직접 넣는 테스트 전용 빌더.
 *
 * <p><b>{@code User.signUp} 이 생겼는데도 SQL 로 넣는다</b> (AU-01). 그 팩터리는 자동 가입만 만들 수 있어서 — {@code
 * PENDING_SIGNUP_INFO} · 닉네임 없음 · 타임스탬프 없음 — 테스트가 필요한 상태를 만들지 못한다. 「가입을 마친 회원」이나 「탈퇴한 회원」, 「무효화 시각이
 * 찍힌 회원」을 팩터리로 만들려면 프로덕션이 쓰지 않는 문을 도메인에 뚫어야 한다. {@code EventFixture} 와 같은 방식으로 넣는다.
 *
 * <p>{@code kakao_user_id} 와 {@code nickname} 에 유니크 제약이 있어(V10) 값을 부르는 쪽이 정하지 않으면 자동으로 겹치지 않게 만든다.
 * 두 테스트가 같은 값을 쓰면 제약 위반이 되고, 그 실패는 검증하려던 것과 아무 상관이 없다.
 */
public final class UserFixture {

  private static final AtomicLong SEQUENCE = new AtomicLong(100_000);

  private static final String INSERT =
      """
      INSERT INTO user (kakao_user_id, nickname, birth_year, status,
                        last_seen_at, tokens_invalidated_at, created_at, updated_at)
      VALUES (?, ?, 1998, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
      """;

  private final long sequence = SEQUENCE.getAndIncrement();

  private SignupStatus status = SignupStatus.ACTIVE;
  private Long kakaoUserId;
  private String nickname;
  private LocalDateTime lastSeenAt;
  private LocalDateTime tokensInvalidatedAt;

  private UserFixture() {}

  public static UserFixture aUser() {
    return new UserFixture();
  }

  /** 닉네임을 지정한다. 안 주면 겹치지 않는 값이 자동으로 붙는다. */
  public UserFixture nickname(String nickname) {
    this.nickname = nickname;
    return this;
  }

  /**
   * 카카오 회원번호를 지정한다. 안 주면 겹치지 않는 값이 자동으로 붙는다.
   *
   * <p>카카오 로그인(AU-01)이 이 값으로 회원을 찾으므로, 「이미 가입한 사람이 다시 로그인한다」를 검증하려면 대역이 돌려줄 번호와 <b>같은 값</b>이 행에 있어야
   * 한다.
   */
  public UserFixture kakaoUserId(long kakaoUserId) {
    this.kakaoUserId = kakaoUserId;
    return this;
  }

  public UserFixture status(SignupStatus status) {
    this.status = status;
    return this;
  }

  public UserFixture lastSeenAt(LocalDateTime lastSeenAt) {
    this.lastSeenAt = lastSeenAt;
    return this;
  }

  public UserFixture tokensInvalidatedAt(LocalDateTime tokensInvalidatedAt) {
    this.tokensInvalidatedAt = tokensInvalidatedAt;
    return this;
  }

  /** 넣은 행의 id 를 준다. 토큰의 {@code sub} 가 그 값이어야 해서 돌려준다. */
  public long insert(JdbcTemplate jdbc) {
    long inserted = kakaoUserId == null ? sequence : kakaoUserId;

    jdbc.update(INSERT, inserted, nickname(), status.name(), lastSeenAt, tokensInvalidatedAt);

    return jdbc.queryForObject("SELECT id FROM user WHERE kakao_user_id = ?", Long.class, inserted);
  }

  /** 가입 정보를 입력하기 전에는 닉네임이 없다 (V10 · AU-05). */
  private String nickname() {
    if (nickname != null) {
      return nickname;
    }
    if (status == SignupStatus.PENDING_SIGNUP_INFO) {
      return null;
    }
    return "덕후" + sequence;
  }
}
