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

/** 모집글 작성 · 수정 · 마감의 검증 기준 중 도메인이 지는 것 (PO-01 · PO-02 · PO-06 · PO-07 · I-04). */
class CompanionPostTest {

  private static final long HOST_ID = 7L;
  private static final long STRANGER_ID = 99L;
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

  @DisplayName("방장이 고치면 제목과 본문과 만남 지점이 바뀐다.")
  @Test
  void editByHost() {
    CompanionPost post = open(kst("2026-09-14T09:00:00+09:00"), null);

    post.editByHost(
        HOST_ID,
        "에이티즈 팝업 오후에 가실 분",
        "오전이 막혀서 시간을 옮겼어요",
        null,
        kst("2026-09-14T15:00:00+09:00"),
        MeetPoint.of("여의도역 3번 출구", new BigDecimal("37.5215"), new BigDecimal("126.9241")),
        Capacity.of(4));

    assertThat(post.getTitle()).isEqualTo("에이티즈 팝업 오후에 가실 분");
    assertThat(post.getContent()).isEqualTo("오전이 막혀서 시간을 옮겼어요");
    assertThat(post.getMeetAt()).isEqualTo(LocalDateTime.of(2026, 9, 14, 6, 0));
    assertThat(post.getMeetPoint().getPlace()).isEqualTo("여의도역 3번 출구");
    assertThat(Capacity.valueOf(post.getCapacity())).isEqualTo(4);
  }

  @DisplayName("방장이 정원을 비우면 정원이 사라진다.")
  @Test
  void editByHost_capacityIsCleared() {
    CompanionPost post =
        CompanionPost.open(
            HOST_ID,
            "제목",
            null,
            null,
            kst("2026-09-14T09:00:00+09:00"),
            MEET_POINT,
            Capacity.of(4));

    post.editByHost(HOST_ID, "제목", null, null, kst("2026-09-14T09:00:00+09:00"), MEET_POINT, null);

    assertThat(post.getCapacity()).isNull();
  }

  @DisplayName("방장이 행사를 바꾸면 행사명과 이미지 스냅샷도 함께 바뀐다.")
  @Test
  void editByHost_replacesEventSnapshot() {
    CompanionPost post = open(kst("2026-09-14T09:00:00+09:00"), chosenEvent());

    post.editByHost(
        HOST_ID,
        "제목",
        null,
        new ChosenEvent(88L, "세븐틴 콘서트", "/event/88.webp", LocalDate.of(2026, 10, 2)),
        kst("2026-10-02T18:00:00+09:00"),
        MEET_POINT,
        null);

    assertThat(post.getEventId()).isEqualTo(88L);
    assertThat(post.getEventTitle()).isEqualTo("세븐틴 콘서트");
    assertThat(post.getEventImageUrl()).isEqualTo("/event/88.webp");
  }

  @DisplayName("방장이 행사를 떼면 행사명과 이미지 스냅샷도 함께 비고 만남시각을 검증하지 않는다.")
  @Test
  void editByHost_clearsEventSnapshot() {
    CompanionPost post = open(kst("2026-09-14T09:00:00+09:00"), chosenEvent());

    post.editByHost(HOST_ID, "제목", null, null, kst("2099-01-01T09:00:00+09:00"), MEET_POINT, null);

    assertThat(post.getEventId()).isNull();
    assertThat(post.getEventTitle()).isNull();
    assertThat(post.getEventImageUrl()).isNull();
  }

  /**
   * I-04 의 검증 시점이 「생성·<b>수정</b> 시」다.
   *
   * <p>바뀐 행사로 판정하는지도 함께 본다 — 종료일이 9월 14일인 행사를 그대로 두고 만남시각만 15일로 미는 요청이다. 옛 스냅샷이 아니라 요청이 가리키는 행사의
   * 종료일을 봐야 한다.
   */
  @DisplayName("고친 만남시각의 KST 날짜가 행사 종료일을 넘으면 모집글을 고칠 수 없다.")
  @Test
  void editByHost_meetAtIsAfterEventEndDate() {
    CompanionPost post = open(kst("2026-09-14T09:00:00+09:00"), chosenEvent());

    assertThatThrownBy(
            () ->
                post.editByHost(
                    HOST_ID,
                    "제목",
                    null,
                    chosenEvent(),
                    kst("2026-09-15T09:00:00+09:00"),
                    MEET_POINT,
                    null))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(PostErrorCode.POST_MEET_AT_AFTER_EVENT_END);
  }

  @DisplayName("마감된 모집글은 고칠 수 없다.")
  @Test
  void editByHost_postIsAlreadyClosed() {
    CompanionPost post = open(kst("2026-09-14T09:00:00+09:00"), null);
    post.closeByHost(HOST_ID);

    assertThatThrownBy(() -> edit(post, HOST_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(PostErrorCode.POST_ALREADY_CLOSED);
  }

  @DisplayName("방장이 아니면 모집글을 고칠 수 없다.")
  @Test
  void editByHost_requesterIsNotHost() {
    CompanionPost post = open(kst("2026-09-14T09:00:00+09:00"), null);

    assertThatThrownBy(() -> edit(post, STRANGER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(PostErrorCode.POST_NOT_HOST);
  }

  @DisplayName("방장이 마감하면 모집이 완료되고 사유가 직접 마감으로 남는다.")
  @Test
  void closeByHost() {
    CompanionPost post = open(kst("2026-09-14T09:00:00+09:00"), null);

    post.closeByHost(HOST_ID);

    assertThat(post.getStatus()).isEqualTo(PostStatus.CLOSED);
    assertThat(post.getClosedReason()).isEqualTo(ClosedReason.MANUAL);
  }

  @DisplayName("이미 마감된 모집글은 다시 마감할 수 없다.")
  @Test
  void closeByHost_postIsAlreadyClosed() {
    CompanionPost post = open(kst("2026-09-14T09:00:00+09:00"), null);
    post.closeByHost(HOST_ID);

    assertThatThrownBy(() -> post.closeByHost(HOST_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(PostErrorCode.POST_ALREADY_CLOSED);
  }

  @DisplayName("방장이 아니면 모집을 마감할 수 없다.")
  @Test
  void closeByHost_requesterIsNotHost() {
    CompanionPost post = open(kst("2026-09-14T09:00:00+09:00"), null);

    assertThatThrownBy(() -> post.closeByHost(STRANGER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(PostErrorCode.POST_NOT_HOST);
  }

  /**
   * 상태를 권한보다 먼저 보기 때문이다.
   *
   * <p>두 가드가 동시에 걸리는 요청에서 어느 코드가 나가는지가 계약이다. 순서를 뒤집으면 같은 요청이 403 을 받게 되고, 그때 클라이언트는 「내 글이 아니다」로 읽는다
   * — 마감된 자기 글에 대해서도 그렇다.
   */
  @DisplayName("남이 마감된 모집글을 마감하려 하면 방장 여부보다 마감 상태를 먼저 알려준다.")
  @Test
  void closeByHost_closedPostReportsStatusBeforeHost() {
    CompanionPost post = open(kst("2026-09-14T09:00:00+09:00"), null);
    post.closeByHost(HOST_ID);

    assertThatThrownBy(() -> post.closeByHost(STRANGER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(PostErrorCode.POST_ALREADY_CLOSED);
  }

  private static CompanionPost open(OffsetDateTime meetAt, ChosenEvent event) {
    return CompanionPost.open(
        HOST_ID, "에이티즈 팝업 오픈런 같이 하실 분", "혼자 가려니...", event, meetAt, MEET_POINT, null);
  }

  /** 가드만 보는 경우에 쓴다. 통과했다면 값이 바뀌는데, 그것은 위의 성공 케이스가 본다. */
  private static void edit(CompanionPost post, long requesterId) {
    post.editByHost(
        requesterId, "고친 제목", null, null, kst("2026-09-14T09:00:00+09:00"), MEET_POINT, null);
  }

  private static ChosenEvent chosenEvent() {
    return new ChosenEvent(41L, "에이티즈 팝업", "/event/41.webp", ENDS_ON);
  }

  private static OffsetDateTime kst(String text) {
    return OffsetDateTime.parse(text);
  }
}
