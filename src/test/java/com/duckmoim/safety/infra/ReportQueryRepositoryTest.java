package com.duckmoim.safety.infra;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.safety.ReportFixture.aReport;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.safety.domain.ReportCursor;
import com.duckmoim.safety.domain.ReportListQuery;
import com.duckmoim.safety.domain.ReportStatus;
import com.duckmoim.safety.domain.ReportTargetType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 백오피스 신고 목록의 정렬 · 필터 · 커서 경계 (AD-02).
 *
 * <p>실제 MySQL 로 돈다. 커서 경계는 정렬과 튜플 비교가 개입해서 mock 으로는 검증되지 않고, 테스트 컨벤션이 H2 도 금지했다.
 *
 * <p><b>이 검사가 특히 필요한 이유</b> — 대상 표시명을 세 곳에서 {@code LEFT JOIN} 으로 끌어오는 쿼리다. 조인 조건이나 {@code COALESCE}
 * 순서가 어긋나도 컴파일은 되고 값만 조용히 비거나 뒤바뀐다.
 *
 * <p>접수 시각을 손으로 고정한다. 같은 시각의 신고가 페이지 경계에 걸리는 경우를 만들어야 한다.
 *
 * <p>유저는 V11 시드를 쓴다 — 2 댓글덕후 · 4 지나가던덕후.
 */
@SpringBootTest
@Transactional
class ReportQueryRepositoryTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 8, 31, 0, 0);

  private static final long REPORTER_ID = 4L;
  private static final long REPORTED_USER_ID = 2L;

  /** 실재하지 않아도 되는 대상 id 를 만든다. 유니크 제약(신고자 · 대상) 때문에 매번 달라야 한다. */
  private static final java.util.concurrent.atomic.AtomicLong TARGET_SEQUENCE =
      new java.util.concurrent.atomic.AtomicLong(9000);

  @Autowired private ReportRepository reportRepository;
  @Autowired private JdbcTemplate jdbc;

  @DisplayName("신고가 최신순으로 나온다.")
  @Test
  void findSlice() {
    long first = userReport(BASE.plusMinutes(1));
    long third = userReport(BASE.plusMinutes(3));
    long second = userReport(BASE.plusMinutes(2));

    assertThat(idsOf(query(null, null, 20))).containsExactly(third, second, first);
  }

  /** AD-02 의 검증 기준이 걸린 자리다 — 「접수 건 전량 조회」. */
  @DisplayName("상태를 주지 않으면 모든 상태가 나온다.")
  @Test
  void findSlice_hasNoStatusFilter() {
    long pending = userReport(BASE.plusMinutes(1), ReportStatus.PENDING);
    long processing = userReport(BASE.plusMinutes(2), ReportStatus.PROCESSING);
    long resolved = userReport(BASE.plusMinutes(3), ReportStatus.RESOLVED);

    assertThat(idsOf(query(null, null, 20))).containsExactly(resolved, processing, pending);
  }

  @DisplayName("상태로 거르면 그 상태의 건만 나온다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(ReportStatus.class)
  void findSlice_filtersByStatus(ReportStatus status) {
    long wanted = userReport(BASE.plusMinutes(1), status);
    for (ReportStatus other : ReportStatus.values()) {
      if (other != status) {
        userReport(BASE.plusMinutes(2), other);
      }
    }

    assertThat(idsOf(query(status, null, 20))).containsExactly(wanted);
  }

  @DisplayName("다음 페이지 유무를 알려고 한 건을 더 읽는다.")
  @Test
  void findSlice_readsOneMore() {
    userReport(BASE.plusMinutes(1));
    userReport(BASE.plusMinutes(2));
    userReport(BASE.plusMinutes(3));

    assertThat(query(null, null, 2)).hasSize(3);
  }

  /** createdAt 만으로 정렬하면 여기서 누락·중복이 난다. */
  @DisplayName("접수 시각이 같아도 id 가 순서를 정한다.")
  @Test
  void findSlice_hasSameCreatedAt() {
    long first = userReport(BASE);
    long second = userReport(BASE);
    long third = userReport(BASE);

    assertThat(idsOf(query(null, null, 20))).containsExactly(third, second, first);
    assertThat(idsOf(query(null, new ReportCursor(BASE, second), 20))).containsExactly(first);
  }

  @DisplayName("커서 다음부터 이어 읽고 앞 페이지를 다시 주지 않는다.")
  @Test
  void findSlice_afterCursor() {
    long first = userReport(BASE.plusMinutes(1));
    long second = userReport(BASE.plusMinutes(2));
    long third = userReport(BASE.plusMinutes(3));

    List<ReportedTarget> next = query(null, new ReportCursor(BASE.plusMinutes(3), third), 20);

    assertThat(idsOf(next)).containsExactly(second, first);
  }

  @DisplayName("상태 필터와 커서를 함께 쓸 수 있다.")
  @Test
  void findSlice_filtersAndReadsAfterCursor() {
    userReport(BASE.plusMinutes(1), ReportStatus.PENDING);
    long wanted = userReport(BASE.plusMinutes(2), ReportStatus.PENDING);
    long cursorId = userReport(BASE.plusMinutes(3), ReportStatus.PENDING);
    userReport(BASE.plusMinutes(4), ReportStatus.RESOLVED);

    List<ReportedTarget> next =
        query(ReportStatus.PENDING, new ReportCursor(BASE.plusMinutes(3), cursorId), 1);

    assertThat(idsOf(next).get(0)).isEqualTo(wanted);
  }

  @DisplayName("유저 신고의 표시명은 그 유저의 닉네임이다.")
  @Test
  void findSlice_carriesUserSubject() {
    aReport()
        .reporterId(REPORTER_ID)
        .target(ReportTargetType.USER, REPORTED_USER_ID)
        .createdAt(BASE)
        .insert(jdbc);

    ReportedTarget found = query(null, null, 20).get(0);

    assertThat(found.reporterNickname()).isEqualTo("지나가던덕후");
    assertThat(found.subject()).isEqualTo("댓글덕후");
    assertThat(found.secret()).isNull();
  }

  @DisplayName("모집글 신고의 표시명은 모집글 제목이다.")
  @Test
  void findSlice_carriesPostSubject() {
    long postId = aCompanionPost().insert(jdbc);
    aReport()
        .reporterId(REPORTER_ID)
        .target(ReportTargetType.POST, postId)
        .createdAt(BASE)
        .insert(jdbc);

    assertThat(query(null, null, 20).get(0).subject()).isNotBlank();
  }

  /** 댓글은 제목이 없고 본문은 목록에 실을 수 없다 (화면 계약). 그래서 작성자 닉네임이다 (STAR-78 에서 정했다). */
  @DisplayName("댓글 신고의 표시명은 댓글 작성자 닉네임이다.")
  @Test
  void findSlice_carriesCommentSubject() {
    long commentId = comment(false, CommentStatus.ACTIVE);
    aReport()
        .reporterId(REPORTER_ID)
        .target(ReportTargetType.COMMENT, commentId)
        .createdAt(BASE)
        .insert(jdbc);

    ReportedTarget found = query(null, null, 20).get(0);

    assertThat(found.subject()).isEqualTo("댓글덕후");
    assertThat(found.secret()).isFalse();
  }

  @DisplayName("비밀 댓글 신고는 secret 이 true 다.")
  @Test
  void findSlice_carriesSecretFlag() {
    long commentId = comment(true, CommentStatus.ACTIVE);
    aReport()
        .reporterId(REPORTER_ID)
        .target(ReportTargetType.COMMENT, commentId)
        .createdAt(BASE)
        .insert(jdbc);

    assertThat(query(null, null, 20).get(0).secret()).isTrue();
  }

  /** 대상이 없어졌다고 큐에서 빼지 않는다 (STAR-60). 조치가 유저 제재로 가므로 처리가 여전히 생산적이다. */
  @DisplayName("지운 댓글·블라인드된 댓글에 대한 신고도 목록에 남는다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = CommentStatus.class,
      names = {"DELETED", "BLINDED"})
  void findSlice_keepsReportsOnInactiveComment(CommentStatus status) {
    long commentId = comment(false, status);
    long reportId =
        aReport()
            .reporterId(REPORTER_ID)
            .target(ReportTargetType.COMMENT, commentId)
            .createdAt(BASE)
            .insert(jdbc);

    assertThat(idsOf(query(null, null, 20))).containsExactly(reportId);
  }

  /** LEFT JOIN 이 아니면 여기서 신고가 통째로 사라진다. */
  @DisplayName("대상 행을 못 찾아도 신고는 목록에 남는다.")
  @Test
  void findSlice_keepsReportWhenTargetIsGone() {
    long reportId =
        aReport()
            .reporterId(REPORTER_ID)
            .target(ReportTargetType.COMMENT, -1L)
            .createdAt(BASE)
            .insert(jdbc);

    ReportedTarget found = query(null, null, 20).get(0);

    assertThat(found.report().getId()).isEqualTo(reportId);
    assertThat(found.subject()).isNull();
  }

  private long comment(boolean secret, CommentStatus status) {
    long postId = aCompanionPost().insert(jdbc);
    return aComment()
        .postId(postId)
        .authorId(REPORTED_USER_ID)
        .secret(secret)
        .status(status)
        .insert(jdbc);
  }

  private long userReport(LocalDateTime createdAt) {
    return userReport(createdAt, ReportStatus.PENDING);
  }

  /**
   * 대상을 매번 다르게 준다. V34 의 유니크 제약이 <b>신고자별 · 대상별</b>이라 같은 짝으로 두 건을 만들 수 없다 (SF-01 의 이중 방어).
   *
   * <p>정렬과 커서 검사에는 대상이 무엇인지가 상관없고, 표시명 검사는 아래에서 실재하는 유저를 따로 쓴다.
   */
  private long userReport(LocalDateTime createdAt, ReportStatus status) {
    return aReport()
        .reporterId(REPORTER_ID)
        .target(ReportTargetType.USER, TARGET_SEQUENCE.incrementAndGet())
        .status(status)
        .createdAt(createdAt)
        .insert(jdbc);
  }

  private List<ReportedTarget> query(ReportStatus status, ReportCursor cursor, int size) {
    return reportRepository.findSlice(new ReportListQuery(status, cursor, size));
  }

  private static List<Long> idsOf(List<ReportedTarget> found) {
    return found.stream().map(target -> target.report().getId()).toList();
  }
}
