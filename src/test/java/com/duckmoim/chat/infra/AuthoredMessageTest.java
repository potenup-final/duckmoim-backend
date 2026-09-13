package com.duckmoim.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.chat.domain.MessageEvent;
import com.duckmoim.chat.domain.MessageStatus;
import com.duckmoim.identity.domain.SignupStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 선로에 실릴 사건 조립 (CH-10 · CH-11).
 *
 * <p><b>이 메서드가 팬아웃과 재연결 재전송이 함께 쓰는 한 자리다.</b> 여기서 끊지 않으면 두 경로가 동시에 샌다 — 목록 조회만 끊어서는 부족하다.
 *
 * <p>스프링을 띄우지 않는다. 입력과 출력이 레코드 둘이다.
 */
@DisplayName("사건 조립")
class AuthoredMessageTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-02T11:10:00Z"), ZoneOffset.UTC);

  @DisplayName("살아 있는 사진 메시지는 본문과 imageId 가 실린다.")
  @Test
  void toEvent_carriesImageOfActiveMessage() {
    MessageEvent event = message(MessageStatus.ACTIVE).toEvent(CLOCK);

    assertThat(event.content()).isEqualTo("사진");
    assertThat(event.imageId()).isEqualTo(7L);
  }

  /**
   * <b>지우거나 가린 메시지는 본문과 사진 번호가 함께 끊긴다</b> (PR #147 리뷰).
   *
   * <p>본문만 끊고 번호를 흘리면 CH-15 가 붙는 순간 그 번호로 서명 URL 이 발급된다. {@code ACTIVE} 가 아닌 상태를 전부 돌려서, 상태가 하나
   * 늘어나는 날에도 이 검사가 그 상태를 덮는다.
   */
  @DisplayName("살아 있지 않은 메시지는 본문과 imageId 가 함께 끊긴다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(value = MessageStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
  void toEvent_hidesImageOfHiddenMessage(MessageStatus status) {
    MessageEvent event = message(status).toEvent(CLOCK);

    assertThat(event.content()).isNull();
    assertThat(event.imageId()).isNull();
  }

  private static AuthoredMessage message(MessageStatus status) {
    return new AuthoredMessage(
        51L,
        3L,
        11L,
        "덕후1",
        null,
        null,
        SignupStatus.ACTIVE,
        "사진",
        7L,
        status,
        LocalDateTime.of(2026, 10, 2, 11, 10));
  }
}
