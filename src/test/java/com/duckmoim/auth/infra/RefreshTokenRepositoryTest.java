package com.duckmoim.auth.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.auth.domain.RefreshToken;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 MySQL 로 돈다 — 테스트 컨벤션이 H2 를 금지했다.
 *
 * <p>여기서 보는 것은 <b>재사용 탐지의 재료</b>다. AU-03 의 검증 기준(「동일 Refresh 2회 사용 시 두 번째 거부」)은 순차이므로, 「회전된 토큰은 해시로
 * 찾히지 않는다」한 줄이면 판정이 성립한다. 거부와 전체 폐기를 실제로 잇는 것은 service 티켓이다.
 */
@SpringBootTest
@Transactional
class RefreshTokenRepositoryTest {

  private static final long USER_ID = 1L;
  private static final long OTHER_USER_ID = 2L;

  @Autowired private RefreshTokenRepository refreshTokenRepository;

  private RefreshToken saved(long userId, String rawToken) {
    return refreshTokenRepository.save(
        RefreshToken.issue(userId, rawToken, LocalDateTime.now(ZoneOffset.UTC).plusDays(14)));
  }

  @Test
  @DisplayName("해시로 저장한 토큰을 찾는다.")
  void findByTokenHash() {
    saved(USER_ID, "raw-1");

    assertThat(refreshTokenRepository.findByTokenHash(RefreshToken.hash("raw-1")))
        .isPresent()
        .get()
        .satisfies(found -> assertThat(found.belongsTo(USER_ID)).isTrue());
  }

  @Test
  @DisplayName("저장하지 않은 해시는 찾지 못한다.")
  void findByTokenHash_notSaved() {
    saved(USER_ID, "raw-1");

    assertThat(refreshTokenRepository.findByTokenHash(RefreshToken.hash("raw-2"))).isEmpty();
  }

  /** 재사용 탐지의 근거다 — 회전으로 행이 사라지면 같은 토큰을 다시 들고 와도 찾히지 않는다 (AU-03). */
  @Test
  @DisplayName("회전으로 지운 토큰은 같은 해시로 다시 찾히지 않는다.")
  void findByTokenHash_rotated() {
    RefreshToken rotated = saved(USER_ID, "raw-1");

    refreshTokenRepository.delete(rotated);

    assertThat(refreshTokenRepository.findByTokenHash(RefreshToken.hash("raw-1"))).isEmpty();
  }

  @Test
  @DisplayName("회원의 토큰을 전부 지운다.")
  void deleteAllByUserId() {
    saved(USER_ID, "phone");
    saved(USER_ID, "laptop");

    refreshTokenRepository.deleteAllByUserId(USER_ID);

    assertThat(refreshTokenRepository.findByTokenHash(RefreshToken.hash("phone"))).isEmpty();
    assertThat(refreshTokenRepository.findByTokenHash(RefreshToken.hash("laptop"))).isEmpty();
  }

  /** 「해당 유저 전체 폐기」가 남의 세션까지 끊으면 안 된다. */
  @Test
  @DisplayName("한 회원의 토큰을 전부 지워도 다른 회원의 토큰은 남는다.")
  void deleteAllByUserId_keepsOtherUsers() {
    saved(USER_ID, "mine");
    saved(OTHER_USER_ID, "someone-elses");

    refreshTokenRepository.deleteAllByUserId(USER_ID);

    assertThat(refreshTokenRepository.findByTokenHash(RefreshToken.hash("someone-elses")))
        .isPresent();
  }
}
