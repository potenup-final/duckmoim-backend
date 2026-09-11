package com.duckmoim.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 채팅 가능 구간의 경계 (CH-08 · I-21).
 *
 * <p>검증 기준이 「만남시각 + 7일 경과 후 전송 시 409」 하나지만, <b>경과 여부의 경계가 어디인지</b>는 그 문장에 없다. 여기서 못박는다.
 *
 * <p><b>시간대가 이 판정의 함정이다.</b> {@code meet_at} 은 UTC 로 저장되고 ({@code CompanionPost#toUtc}) {@code
 * ClockConfig} 의 시계는 {@code Asia/Seoul} 이다. 그래서 <b>KST 시계를 쓰는 테스트가 이 클래스의 본론</b>이다 — UTC 시계로만 짜면 아홉
 * 시간 어긋나는 구현도 초록불이 된다.
 *
 * <p>{@code MeetTimePassedCloseBatch#nowInUtc} 가 같은 컬럼에서 같은 함정을 겪고 자바독으로 남겼다.
 *
 * <p><b>{@code isMember} 는 여기 없다.</b> 방 상세(CH-06)가 같은 메서드를 쓰고 그쪽 테스트가 본다 — 이 클래스는 CH-08 의 경계만 진다.
 */
@DisplayName("채팅 가능 구간")
class ChatRoomWritableTest {

  private static final long POST_ID = 1L;
  private static final long HOST_ID = 7L;

  /** 만남시각. UTC 로 저장되는 값이라 UTC 로 적는다 — KST 로는 2026-10-01 18:00 이다. */
  private static final LocalDateTime MEET_AT_UTC = LocalDateTime.of(2026, 10, 1, 9, 0);

  /** 읽기 전용이 되는 순간. 만남시각 + 7일 (UTC). */
  private static final LocalDateTime DEADLINE_UTC = MEET_AT_UTC.plusDays(7);

  @DisplayName("만남 직후에는 쓸 수 있다.")
  @Test
  void writableRightAfterMeeting() {
    assertThat(roomAt(MEET_AT_UTC.plusMinutes(1))).isTrue();
  }

  /** 경계를 「지났는가」로 판정한다 — 딱 그 순간은 아직 지나지 않았다. */
  @DisplayName("만남시각 + 7일 정각에는 아직 쓸 수 있다.")
  @Test
  void writableAtDeadline() {
    assertThat(roomAt(DEADLINE_UTC)).isTrue();
  }

  @DisplayName("만남시각 + 7일을 1초라도 넘기면 쓸 수 없다.")
  @Test
  void notWritableJustAfterDeadline() {
    assertThat(roomAt(DEADLINE_UTC.plusSeconds(1))).isFalse();
  }

  /**
   * <b>이 테스트가 아홉 시간 어긋난 구현을 잡는다.</b>
   *
   * <p>UTC 로 마감 6일 20시간 뒤 — 아직 쓸 수 있어야 한다. 그런데 구현이 {@code LocalDateTime.now(kstClock)} 을 그대로 쓰면 그
   * 값이 UTC 보다 아홉 시간 앞서서 7일 5시간으로 읽히고 <b>여기서 false 가 된다.</b> 사용자는 마지막 날 대화를 잃는다.
   */
  @DisplayName("KST 시계를 써도 UTC 로 저장된 만남시각과 어긋나지 않는다.")
  @Test
  void writableIsJudgedInUtcEvenWithKstClock() {
    Clock kstClock = fixedAt(DEADLINE_UTC.minusHours(4), ZoneId.of("Asia/Seoul"));

    assertThat(ChatRoom.openFor(POST_ID, HOST_ID).isWritable(MEET_AT_UTC, kstClock)).isTrue();
  }

  /** 위 테스트의 짝이다. 어긋난 구현은 이쪽에서 반대로 true 를 낸다. */
  @DisplayName("KST 시계를 써도 구간이 지난 뒤에는 쓸 수 없다.")
  @Test
  void notWritableIsJudgedInUtcEvenWithKstClock() {
    Clock kstClock = fixedAt(DEADLINE_UTC.plusHours(4), ZoneId.of("Asia/Seoul"));

    assertThat(ChatRoom.openFor(POST_ID, HOST_ID).isWritable(MEET_AT_UTC, kstClock)).isFalse();
  }

  private boolean roomAt(LocalDateTime nowInUtc) {
    return ChatRoom.openFor(POST_ID, HOST_ID)
        .isWritable(MEET_AT_UTC, fixedAt(nowInUtc, ZoneOffset.UTC));
  }

  private static Clock fixedAt(LocalDateTime instantInUtc, ZoneId zone) {
    return Clock.fixed(instantInUtc.toInstant(ZoneOffset.UTC), zone);
  }
}
