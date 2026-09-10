package com.duckmoim.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 탈퇴 익명화 (AU-11).
 *
 * <p>탈퇴 쪽만 보면 절반이다. <b>활동 중인 회원이 그대로 나가는 것</b>을 함께 못박지 않으면, 조건을 뒤집어 모두를 익명화해도 초록불이 난다.
 *
 * <p>시계를 고정한다. {@code lastSeen} 이 경과 시간으로 갈리는 값이라 놓아두면 같은 데이터가 어제와 오늘 다르게 나온다 ({@link LastSeenTest}
 * 와 같은 이유).
 */
class AuthorDisplayTest {

  private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 9, 8, 3, 0);

  private static final Clock FIXED =
      Clock.fixed(NOW_UTC.toInstant(ZoneOffset.UTC), ZoneId.of("Asia/Seoul"));

  @DisplayName("탈퇴한 회원의 닉네임은 자리표시자로 바뀐다.")
  @Test
  void withdrawnNickname() {
    AuthorDisplay display =
        AuthorDisplay.of(SignupStatus.WITHDRAWN, "덕질하는오리", "/avatar/a1.webp", NOW_UTC, FIXED);

    assertThat(display.nickname()).isEqualTo("탈퇴한 회원");
  }

  /** 이름만 지우고 사진을 남기면 익명화가 성립하지 않는다. 사진이 이름보다 더 식별적이다. */
  @DisplayName("탈퇴한 회원의 프로필 이미지는 내려가지 않는다.")
  @Test
  void withdrawnProfileImage() {
    AuthorDisplay display =
        AuthorDisplay.of(SignupStatus.WITHDRAWN, "덕질하는오리", "/avatar/a1.webp", NOW_UTC, FIXED);

    assertThat(display.profileImageUrl()).isNull();
  }

  /**
   * 남기면 「탈퇴한 회원 · 오늘 접속」 이 뜬다. 방금 탈퇴한 계정을 골라 그 함정을 그대로 재현한다 — {@code lastSeenAt} 이 탈퇴 시점 값으로 남아 있어
   * 익명화하지 않으면 {@code TODAY} 로 계산된다.
   */
  @DisplayName("탈퇴한 회원의 최근 접속은 내려가지 않는다.")
  @Test
  void withdrawnLastSeen() {
    AuthorDisplay display =
        AuthorDisplay.of(SignupStatus.WITHDRAWN, "덕질하는오리", "/avatar/a1.webp", NOW_UTC, FIXED);

    assertThat(display.lastSeen()).isNull();
  }

  @DisplayName("활동 중인 회원의 작성자 값은 그대로 나간다.")
  @Test
  void active() {
    AuthorDisplay display =
        AuthorDisplay.of(SignupStatus.ACTIVE, "덕질하는오리", "/avatar/a1.webp", NOW_UTC, FIXED);

    assertThat(display).isEqualTo(new AuthorDisplay("덕질하는오리", "/avatar/a1.webp", LastSeen.TODAY));
  }

  /** 관측된 적이 없으면 {@code LONG_AGO} 가 아니라 {@code null} 이다 (AU-03 이 재발급 시점에만 갱신한다). */
  @DisplayName("한 번도 관측되지 않은 최근 접속은 그대로 비어 나간다.")
  @Test
  void neverSeen() {
    AuthorDisplay display =
        AuthorDisplay.of(SignupStatus.ACTIVE, "덕질하는오리", "/avatar/a1.webp", null, FIXED);

    assertThat(display.lastSeen()).isNull();
  }
}
