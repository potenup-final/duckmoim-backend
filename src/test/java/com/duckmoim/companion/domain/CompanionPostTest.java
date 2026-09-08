package com.duckmoim.companion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.exception.PostErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 모집글 작성의 검증 기준 중 도메인이 지는 것 (PO-01 · PO-02 · I-04). */
class CompanionPostTest {

  private static final long HOST_ID = 7L;
  private static final LocalDate ENDS_ON = LocalDate.of(2026, 9, 14);

  private static final MeetPoint MEET_POINT =
      MeetPoint.of("더현대 서울 지하 1층", new BigDecimal("37.5256381"), new BigDecimal("126.9289384"));

  @DisplayName("작성한 모집글은 모집중으로 시작한다.")
  @Test
  void open() {
    CompanionPost post = open(kst("2026-09-14T09:00:00+09:00"), null);

    assertThat(post.getStatus()).isEqualTo(PostStatus.OPEN);
    assertThat(post.getClosedReason()).isNull();
    assertThat(post.getHostId()).isEqualTo(HOST_ID);
  }

  @DisplayName("만남시각은 UTC 로 저장된다.")
  @Test
  void open_storesMeetAtInUtc() {
    CompanionPost post = open(kst("2026-09-14T09:00:00+09:00"), null);

    assertThat(post.getMeetAt()).isEqualTo(LocalDateTime.of(2026, 9, 14, 0, 0));
  }

  @DisplayName("행사를 고르면 행사명과 이미지를 스냅샷으로 갖는다.")
  @Test
  void open_copiesEventSnapshot() {
    CompanionPost post = open(kst("2026-09-14T09:00:00+09:00"), chosenEvent());

    assertThat(post.getEventId()).isEqualTo(41L);
    assertThat(post.getEventTitle()).isEqualTo("에이티즈 팝업");
    assertThat(post.getEventImageUrl()).isEqualTo("/event/41.webp");
  }

  @DisplayName("만남시각의 KST 날짜가 행사 종료일과 같으면 모집글을 작성할 수 있다.")
  @Test
  void open_meetAtIsOnEventEndDate() {
    assertThatCode(() -> open(kst("2026-09-14T23:30:00+09:00"), chosenEvent()))
        .doesNotThrowAnyException();
  }

  @DisplayName("만남시각의 KST 날짜가 행사 종료일을 넘으면 모집글을 작성할 수 없다.")
  @Test
  void open_meetAtIsAfterEventEndDate() {
    assertThatThrownBy(() -> open(kst("2026-09-15T09:00:00+09:00"), chosenEvent()))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(PostErrorCode.POST_MEET_AT_AFTER_EVENT_END);
  }

  /**
   * KST 로 읽어야 하는 이유를 그대로 세운 경우다 (I-04).
   *
   * <p>KST 9월 15일 0시 30분은 UTC 로는 아직 9월 14일이다. 종료일이 14일인 행사에 이 만남시각은 <b>하루 넘긴 것</b>인데, UTC 날짜로 비교하면
   * 통과해 버린다. 매일 아홉 시간씩 답이 갈리는 구간이 있다.
   */
  @DisplayName("만남시각이 UTC 로는 종료일 안이어도 KST 로 넘겼으면 작성할 수 없다.")
  @Test
  void open_meetAtIsAfterEventEndDateOnlyInKst() {
    assertThatThrownBy(() -> open(kst("2026-09-15T00:30:00+09:00"), chosenEvent()))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(PostErrorCode.POST_MEET_AT_AFTER_EVENT_END);
  }

  @DisplayName("행사를 고르지 않으면 만남시각을 검증하지 않는다.")
  @Test
  void open_eventIsNotChosen() {
    CompanionPost post = open(kst("2099-01-01T09:00:00+09:00"), null);

    assertThat(post.getEventId()).isNull();
    assertThat(post.getEventTitle()).isNull();
    assertThat(post.getEventImageUrl()).isNull();
  }

  @DisplayName("정원을 넣지 않은 모집글은 정원을 갖지 않는다.")
  @Test
  void open_capacityIsAbsent() {
    CompanionPost post = open(kst("2026-09-14T09:00:00+09:00"), null);

    assertThat(post.getCapacity()).isNull();
  }

  private static CompanionPost open(OffsetDateTime meetAt, ChosenEvent event) {
    return CompanionPost.open(
        HOST_ID, "에이티즈 팝업 오픈런 같이 하실 분", "혼자 가려니...", event, meetAt, MEET_POINT, null);
  }

  private static ChosenEvent chosenEvent() {
    return new ChosenEvent(41L, "에이티즈 팝업", "/event/41.webp", ENDS_ON);
  }

  private static OffsetDateTime kst(String text) {
    return OffsetDateTime.parse(text);
  }
}
