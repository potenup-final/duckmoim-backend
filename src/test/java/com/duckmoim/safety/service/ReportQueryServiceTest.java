package com.duckmoim.safety.service;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.safety.ReportFixture.aReport;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.safety.domain.ReportCursor;
import com.duckmoim.safety.domain.ReportListQuery;
import com.duckmoim.safety.domain.ReportStatus;
import com.duckmoim.safety.domain.ReportTargetType;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신고 큐 한 페이지의 조립 (AD-02).
 *
 * <p>정렬 · 필터 · 커서 경계는 {@code ReportQueryRepositoryTest} 가 본다. 여기서는 <b>페이지를 어떻게 자르고 다음 커서를 어디로
 * 가리키는지</b>, 그리고 <b>저장소 결과가 화면 값으로 옮겨지는지</b>를 본다.
 */
@SpringBootTest
@Transactional
class ReportQueryServiceTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 8, 31, 0, 0);

  private static final long REPORTER_ID = 4L;
  private static final long COMMENT_AUTHOR_ID = 2L;

  private static final AtomicLong TARGET_SEQUENCE = new AtomicLong(9000);

  @Autowired private ReportQueryService reportQueryService;
  @Autowired private JdbcTemplate jdbc;

  @DisplayName("한 페이지를 size 만큼 자르고 다음 커서를 준다.")
  @Test
  void findReports() {
    userReport(BASE.plusMinutes(1));
    long second = userReport(BASE.plusMinutes(2));
    long third = userReport(BASE.plusMinutes(3));

    ReportSlice slice = reportQueryService.findReports(query(null, null, 2));

    assertThat(slice.items()).extracting(ReportView::id).containsExactly(third, second);
    assertThat(slice.hasNext()).isTrue();
    assertThat(slice.nextCursor()).isEqualTo(new ReportCursor(BASE.plusMinutes(2), second));
  }

  @DisplayName("마지막 페이지에서는 nextCursor 가 없다.")
  @Test
  void findReports_isLastPage() {
    userReport(BASE.plusMinutes(1));

    ReportSlice slice = reportQueryService.findReports(query(null, null, 20));

    assertThat(slice.hasNext()).isFalse();
    assertThat(slice.nextCursor()).isNull();
  }

  @DisplayName("다음 커서로 이어 읽으면 겹치지 않는다.")
  @Test
  void findReports_readsNextPage() {
    long first = userReport(BASE.plusMinutes(1));
    userReport(BASE.plusMinutes(2));
    userReport(BASE.plusMinutes(3));

    ReportSlice page = reportQueryService.findReports(query(null, null, 2));
    ReportSlice next = reportQueryService.findReports(query(null, page.nextCursor(), 2));

    assertThat(next.items()).extracting(ReportView::id).containsExactly(first);
    assertThat(next.hasNext()).isFalse();
  }

  @DisplayName("신고가 없으면 빈 페이지다.")
  @Test
  void findReports_isEmpty() {
    ReportSlice slice = reportQueryService.findReports(query(null, null, 20));

    assertThat(slice.items()).isEmpty();
    assertThat(slice.hasNext()).isFalse();
    assertThat(slice.nextCursor()).isNull();
  }

  @DisplayName("저장소가 읽은 값이 화면 값으로 옮겨진다.")
  @Test
  void findReports_carriesFields() {
    long targetId = userReport(BASE);

    ReportView view = reportQueryService.findReports(query(null, null, 20)).items().get(0);

    assertThat(view.id()).isEqualTo(targetId);
    assertThat(view.targetType()).isEqualTo(ReportTargetType.USER);
    assertThat(view.reporter()).isEqualTo("지나가던덕후");
    assertThat(view.status()).isEqualTo(ReportStatus.PENDING);
    assertThat(view.result()).isNull();
    assertThat(view.memo()).isNull();
    assertThat(view.detail()).isNotBlank();
  }

  /** 대상이 댓글이 아니면 저장소가 null 을 준다. 화면에는 false 로 내린다. */
  @DisplayName("댓글이 아닌 신고의 secret 은 false 다.")
  @Test
  void findReports_hasNoSecretForNonComment() {
    userReport(BASE);

    assertThat(reportQueryService.findReports(query(null, null, 20)).items().get(0).secret())
        .isFalse();
  }

  @DisplayName("비밀 댓글 신고의 secret 은 true 다.")
  @Test
  void findReports_hasSecretForSecretComment() {
    long postId = aCompanionPost().insert(jdbc);
    long commentId =
        aComment().postId(postId).authorId(COMMENT_AUTHOR_ID).secret(true).insert(jdbc);
    aReport()
        .reporterId(REPORTER_ID)
        .target(ReportTargetType.COMMENT, commentId)
        .createdAt(BASE)
        .insert(jdbc);

    ReportView view = reportQueryService.findReports(query(null, null, 20)).items().get(0);

    assertThat(view.secret()).isTrue();
    assertThat(view.subject()).isEqualTo("댓글덕후");
  }

  private long userReport(LocalDateTime createdAt) {
    return aReport()
        .reporterId(REPORTER_ID)
        .target(ReportTargetType.USER, TARGET_SEQUENCE.incrementAndGet())
        .createdAt(createdAt)
        .insert(jdbc);
  }

  private static ReportListQuery query(ReportStatus status, ReportCursor cursor, int size) {
    return new ReportListQuery(status, cursor, size);
  }
}
