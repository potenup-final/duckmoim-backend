package com.duckmoim.companion.service;

import static com.duckmoim.catalog.EventFixture.anEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.catalog.exception.EventErrorCode;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.domain.ClosedReason;
import com.duckmoim.companion.domain.PostStatus;
import com.duckmoim.companion.exception.PostErrorCode;
import com.duckmoim.companion.infra.CompanionPostRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모집글 작성 · 수정 · 마감의 검증 기준 (PO-01 · PO-02 · PO-03 · PO-05 · PO-06 · PO-07).
 *
 * <p>실제 MySQL 로 돈다. 외부 식별자로 행사를 풀어 오는 경로와 정원 체크 제약이 저장소를 지나야 검증되고, 테스트 컨벤션이 H2 도 금지했다.
 *
 * <p>가드 자체는 도메인 단위 테스트가 본다 ({@code CompanionPostTest}). 여기서 보는 것은 <b>저장을 지나야 확인되는 것</b>이다 — 스냅샷이
 * 실제로 갱신되는지, 마감 사유가 컬럼에 앉는지, 그리고 없는 글·없는 행사의 조회 실패.
 *
 * <p>동시성 테스트가 없다. 작성 경로에 유니크 제약도 증가시킬 카운트도 없고 (정원은 표시용이라 참여 인원을 세지 않는다. PO-05), 마감은 도메인-모델링.md 「3.1
 * 경계와 트랜잭션 범위」가 <i>"닫힌 글도 열람은 되고 사용자가 잃는 것이 없어 막지 않는다"</i> 며 락을 두지 않기로 정했다.
 */
@SpringBootTest
@Transactional
class CompanionPostCommandServiceTest {

  private static final long HOST_ID = 7L;

  /** 시드(V3)에 없는 값이어야 한다. {@code uk_event_external_id} 가 유니크라 겹치면 픽스처가 저장에서 터진다. */
  private static final String EXTERNAL_ID = "pg_test_8417";

  private static final BigDecimal LAT = new BigDecimal("37.5256381");
  private static final BigDecimal LNG = new BigDecimal("126.9289384");

  @Autowired private CompanionPostCommandService companionPostCommandService;
  @Autowired private CompanionPostRepository companionPostRepository;
  @Autowired private JdbcTemplate jdbc;

  @DisplayName("필수 셋만으로 모집글을 작성한다.")
  @Test
  void create() {
    WrittenCompanionPost written = companionPostCommandService.create(command(null, null, null));
    companionPostRepository.flush();

    assertThat(written.id()).isNotNull();
    assertThat(written.status()).isEqualTo(PostStatus.OPEN);
    assertThat(written.eventId()).isNull();
    assertThat(written.capacity()).isNull();
    assertThat(storedLong(written.id(), "host_id")).isEqualTo(HOST_ID);
  }

  @DisplayName("만남지점은 장소명과 좌표가 함께 저장된다.")
  @Test
  void create_storesMeetPoint() {
    WrittenCompanionPost written = companionPostCommandService.create(command(null, null, null));
    companionPostRepository.flush();

    assertThat(written.meetPoint().getPlace()).isEqualTo("더현대 서울 지하 1층 팝업 아이코닉");
    assertThat(storedString(written.id(), "meet_place")).isEqualTo("더현대 서울 지하 1층 팝업 아이코닉");
  }

  @DisplayName("만남시각은 UTC 로 저장된다.")
  @Test
  void create_storesMeetAtInUtc() {
    WrittenCompanionPost written = companionPostCommandService.create(command(null, null, null));
    companionPostRepository.flush();

    assertThat(written.meetAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
  }

  @DisplayName("행사를 고르면 행사명과 이미지를 스냅샷으로 갖는다.")
  @Test
  void create_copiesEventSnapshot() {
    givenEvent("에이티즈 X 애니티즈 팝업", LocalDate.of(2026, 10, 31));

    WrittenCompanionPost written =
        companionPostCommandService.create(command(EXTERNAL_ID, null, null));
    companionPostRepository.flush();

    assertThat(written.eventId()).isEqualTo(EXTERNAL_ID);
    assertThat(written.eventTitle()).isEqualTo("에이티즈 X 애니티즈 팝업");
    assertThat(written.eventImageUrl()).isEqualTo("https://cdn.example.test/8417.webp");
  }

  @DisplayName("원제가 없는 행사를 고르면 대상명이 행사명이 된다.")
  @Test
  void create_eventHasNoTitle() {
    givenEvent(null, LocalDate.of(2026, 10, 31));

    WrittenCompanionPost written =
        companionPostCommandService.create(command(EXTERNAL_ID, null, null));

    assertThat(written.eventTitle()).isEqualTo("에이티즈");
  }

  @DisplayName("없는 행사로는 모집글을 작성할 수 없다.")
  @Test
  void create_eventIsMissing() {
    assertThatThrownBy(() -> companionPostCommandService.create(command("pg_없는행사", null, null)))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(EventErrorCode.EVENT_NOT_FOUND);
  }

  @DisplayName("만남시각의 KST 날짜가 행사 종료일을 넘으면 모집글을 작성할 수 없다.")
  @Test
  void create_meetAtIsAfterEventEndDate() {
    givenEvent("에이티즈 X 애니티즈 팝업", LocalDate.of(2026, 9, 30));

    assertThatThrownBy(() -> companionPostCommandService.create(command(EXTERNAL_ID, null, null)))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(PostErrorCode.POST_MEET_AT_AFTER_EVENT_END);
  }

  @DisplayName("행사를 고르지 않으면 만남시각을 검증하지 않는다.")
  @Test
  void create_eventIsNotChosen() {
    WrittenCompanionPost written =
        companionPostCommandService.create(
            command(null, null, OffsetDateTime.parse("2099-01-01T09:00:00+09:00")));

    assertThat(written.id()).isNotNull();
  }

  @DisplayName("정원이 2~6이면 그대로 저장된다.")
  @ParameterizedTest
  @ValueSource(ints = {2, 6})
  void create_withCapacity(int capacity) {
    WrittenCompanionPost written =
        companionPostCommandService.create(command(null, capacity, null));
    companionPostRepository.flush();

    assertThat(written.capacity()).isEqualTo(capacity);
    assertThat(storedLong(written.id(), "capacity")).isEqualTo(capacity);
  }

  @DisplayName("정원이 2~6 밖이면 모집글을 작성할 수 없다.")
  @ParameterizedTest
  @ValueSource(ints = {1, 7})
  void create_capacityIsOutOfRange(int capacity) {
    assertThatThrownBy(() -> companionPostCommandService.create(command(null, capacity, null)))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(PostErrorCode.POST_CAPACITY_OUT_OF_RANGE);
  }

  @DisplayName("방장이 고친 값이 저장된다.")
  @Test
  void edit() {
    Long postId = companionPostCommandService.create(command(null, null, null)).id();

    WrittenCompanionPost edited =
        companionPostCommandService.edit(editCommand(postId, HOST_ID, null, 4));
    companionPostRepository.flush();

    assertThat(edited.title()).isEqualTo("에이티즈 팝업 오후에 가실 분");
    assertThat(edited.capacity()).isEqualTo(4);
    assertThat(storedString(postId, "title")).isEqualTo("에이티즈 팝업 오후에 가실 분");
    assertThat(storedLong(postId, "capacity")).isEqualTo(4);
  }

  @DisplayName("방장이 행사를 붙이면 행사명과 이미지 스냅샷도 함께 저장된다.")
  @Test
  void edit_replacesEventSnapshot() {
    givenEvent("에이티즈 X 애니티즈 팝업", LocalDate.of(2026, 10, 31));
    Long postId = companionPostCommandService.create(command(null, null, null)).id();

    WrittenCompanionPost edited =
        companionPostCommandService.edit(editCommand(postId, HOST_ID, EXTERNAL_ID, null));
    companionPostRepository.flush();

    assertThat(edited.eventId()).isEqualTo(EXTERNAL_ID);
    assertThat(edited.eventTitle()).isEqualTo("에이티즈 X 애니티즈 팝업");
    assertThat(storedString(postId, "event_title")).isEqualTo("에이티즈 X 애니티즈 팝업");
  }

  @DisplayName("없는 모집글은 고칠 수 없다.")
  @Test
  void edit_postIsMissing() {
    assertThatThrownBy(
            () -> companionPostCommandService.edit(editCommand(404_404L, HOST_ID, null, null)))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(PostErrorCode.POST_NOT_FOUND);
  }

  @DisplayName("없는 행사로는 모집글을 고칠 수 없다.")
  @Test
  void edit_eventIsMissing() {
    Long postId = companionPostCommandService.create(command(null, null, null)).id();

    assertThatThrownBy(
            () -> companionPostCommandService.edit(editCommand(postId, HOST_ID, "pg_없는행사", null)))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(EventErrorCode.EVENT_NOT_FOUND);
  }

  @DisplayName("방장이 마감하면 저장된 상태와 사유가 바뀐다.")
  @Test
  void close() {
    Long postId = companionPostCommandService.create(command(null, null, null)).id();

    ClosedCompanionPost closed = companionPostCommandService.close(postId, HOST_ID);
    companionPostRepository.flush();

    assertThat(closed.status()).isEqualTo(PostStatus.CLOSED);
    assertThat(closed.closedReason()).isEqualTo(ClosedReason.MANUAL);
    assertThat(storedString(postId, "status")).isEqualTo("CLOSED");
    assertThat(storedString(postId, "closed_reason")).isEqualTo("MANUAL");
  }

  @DisplayName("없는 모집글은 마감할 수 없다.")
  @Test
  void close_postIsMissing() {
    assertThatThrownBy(() -> companionPostCommandService.close(404_404L, HOST_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(PostErrorCode.POST_NOT_FOUND);
  }

  /** 시작일을 함께 옮긴다. {@code ck_event_period} 가 종료일이 시작일보다 앞서는 행을 막는다. */
  private void givenEvent(String title, LocalDate endsOn) {
    anEvent()
        .externalId(EXTERNAL_ID)
        .subject("에이티즈")
        .title(title)
        .startsOn(endsOn.minusDays(7))
        .endsOn(endsOn)
        .imageUrl("https://cdn.example.test/8417.webp")
        .regionId(1)
        .insert(jdbc);
  }

  private static CompanionPostWriteCommand command(
      String eventExternalId, Integer capacity, OffsetDateTime meetAt) {

    return new CompanionPostWriteCommand(
        HOST_ID,
        "에이티즈 팝업 오픈런 같이 하실 분",
        "혼자 가려니...",
        eventExternalId,
        meetAt == null ? OffsetDateTime.parse("2026-10-01T09:00:00+09:00") : meetAt,
        "더현대 서울 지하 1층 팝업 아이코닉",
        LAT,
        LNG,
        capacity);
  }

  private static CompanionPostEditCommand editCommand(
      Long postId, Long requesterId, String eventExternalId, Integer capacity) {

    return new CompanionPostEditCommand(
        postId,
        requesterId,
        "에이티즈 팝업 오후에 가실 분",
        "오전이 막혀서 시간을 옮겼어요",
        eventExternalId,
        OffsetDateTime.parse("2026-10-01T15:00:00+09:00"),
        "여의도역 3번 출구",
        LAT,
        LNG,
        capacity);
  }

  private Long storedLong(long postId, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM companion_post WHERE id = ?", Long.class, postId);
  }

  private String storedString(long postId, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM companion_post WHERE id = ?", String.class, postId);
  }
}
