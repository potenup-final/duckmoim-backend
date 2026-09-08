package com.duckmoim.companion.infra;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.domain.MyCommentCursor;
import com.duckmoim.companion.domain.MyCommentListQuery;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 댓글 내역의 정렬 · 필터 · 커서 경계 (CM-16).
 *
 * <p>실제 MySQL 로 돈다. 커서 경계는 정렬과 튜플 비교가 개입해서 mock 으로는 검증되지 않고, 테스트 컨벤션이 H2 도 금지했다.
 *
 * <p><b>작성 시각을 손으로 고정한다.</b> 정렬 키가 같은 데이터를 만들어 경계를 봐야 하는데 {@code UTC_TIMESTAMP(6)} 에 맡기면 마이크로초가 갈려
 * 같은 시각이 만들어지지 않는다.
 *
 * <p>작성자는 V11 시드를 쓴다 — 2 가 나, 4 가 남이다.
 */
@SpringBootTest
@Transactional
class MyCommentQueryRepositoryTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 1, 0, 0);

  private static final long ME = 2L;
  private static final long SOMEONE_ELSE = 4L;

  @Autowired private CommentRepository commentRepository;
  @Autowired private JdbcTemplate jdbc;

  private long postId;

  @BeforeEach
  void setUp() {
    postId = aCompanionPost().insert(jdbc);
  }

  @DisplayName("내 댓글은 작성 최신순으로 온다.")
  @Test
  void findMySlice() {
    long oldest = mine(BASE.plusMinutes(1));
    long newest = mine(BASE.plusMinutes(3));
    long middle = mine(BASE.plusMinutes(2));

    List<MyComment> found = commentRepository.findMySlice(query(null, 20));

    assertThat(idsOf(found)).containsExactly(newest, middle, oldest);
  }

  @DisplayName("남이 쓴 댓글은 내 내역에 오지 않는다.")
  @Test
  void findMySlice_excludesOthers() {
    long mine = mine(BASE.plusMinutes(1));
    aComment().postId(postId).authorId(SOMEONE_ELSE).createdAt(BASE.plusMinutes(2)).insert(jdbc);

    List<MyComment> found = commentRepository.findMySlice(query(null, 20));

    assertThat(idsOf(found)).containsExactly(mine);
  }

  /** 도메인 7.1 이 삭제·블라인드 본문을 작성자 본인에게도 막는다. 남겨도 본문 없는 껍데기만 뜬다. */
  @DisplayName("삭제한 내 댓글은 내 내역에서 빠진다.")
  @Test
  void findMySlice_excludesDeleted() {
    long alive = mine(BASE.plusMinutes(1));
    mine(BASE.plusMinutes(2), CommentStatus.DELETED);

    List<MyComment> found = commentRepository.findMySlice(query(null, 20));

    assertThat(idsOf(found)).containsExactly(alive);
  }

  @DisplayName("블라인드된 내 댓글도 내 내역에서 빠진다.")
  @Test
  void findMySlice_excludesBlinded() {
    long alive = mine(BASE.plusMinutes(1));
    mine(BASE.plusMinutes(2), CommentStatus.BLINDED);

    List<MyComment> found = commentRepository.findMySlice(query(null, 20));

    assertThat(idsOf(found)).containsExactly(alive);
  }

  /** 내 내역의 커서는 내가 쓴 댓글을 센다. 목록과 달리 루트와 대댓글을 가르지 않는다. */
  @DisplayName("내가 쓴 대댓글도 내 내역에 온다.")
  @Test
  void findMySlice_includesReplies() {
    long root = aComment().postId(postId).authorId(SOMEONE_ELSE).createdAt(BASE).insert(jdbc);
    long myReply =
        aComment()
            .postId(postId)
            .authorId(ME)
            .parentId(root)
            .createdAt(BASE.plusMinutes(1))
            .insert(jdbc);

    List<MyComment> found = commentRepository.findMySlice(query(null, 20));

    assertThat(idsOf(found)).containsExactly(myReply);
  }

  @DisplayName("내 비밀 댓글도 내 내역에 온다.")
  @Test
  void findMySlice_includesSecret() {
    long secret = aComment().postId(postId).authorId(ME).secret(true).createdAt(BASE).insert(jdbc);

    List<MyComment> found = commentRepository.findMySlice(query(null, 20));

    assertThat(idsOf(found)).containsExactly(secret);
    assertThat(found.get(0).comment().isSecret()).isTrue();
  }

  /** CM-16 이 요구하는 항목 셋 중 하나다. 모집글을 ID 로만 참조하므로 제목은 조인으로 얻는다 (도메인 3.2). */
  @DisplayName("내 댓글에는 모집글 제목이 함께 온다.")
  @Test
  void findMySlice_carriesPostTitle() {
    long otherPostId = aCompanionPost().title("에이티즈 팝업 오픈런 같이 하실 분").insert(jdbc);
    aComment().postId(otherPostId).authorId(ME).createdAt(BASE).insert(jdbc);

    List<MyComment> found = commentRepository.findMySlice(query(null, 20));

    assertThat(found).hasSize(1);
    assertThat(found.get(0).postTitle()).isEqualTo("에이티즈 팝업 오픈런 같이 하실 분");
  }

  @DisplayName("내 내역은 모집글을 가로질러 모인다.")
  @Test
  void findMySlice_spansPosts() {
    long here = mine(BASE.plusMinutes(1));
    long there =
        aComment()
            .postId(aCompanionPost().insert(jdbc))
            .authorId(ME)
            .createdAt(BASE.plusMinutes(2))
            .insert(jdbc);

    List<MyComment> found = commentRepository.findMySlice(query(null, 20));

    assertThat(idsOf(found)).containsExactly(there, here);
  }

  @DisplayName("다음 페이지 유무를 알려고 한 건을 더 읽는다.")
  @Test
  void findMySlice_readsOneMore() {
    mine(BASE.plusMinutes(1));
    mine(BASE.plusMinutes(2));
    mine(BASE.plusMinutes(3));

    assertThat(commentRepository.findMySlice(query(null, 2))).hasSize(3);
  }

  /** 정렬 키가 같은 데이터를 일부러 만들어 경계를 본다 (테스트 컨벤션). 최신순이라 id 도 큰 것이 먼저다. */
  @DisplayName("작성 시각이 같아도 id 가 순서를 정한다.")
  @Test
  void findMySlice_hasSameCreatedAt() {
    long first = mine(BASE);
    long second = mine(BASE);
    long third = mine(BASE);

    List<MyComment> page = commentRepository.findMySlice(query(null, 2));
    assertThat(idsOf(page)).containsExactly(third, second, first);

    List<MyComment> next =
        commentRepository.findMySlice(query(new MyCommentCursor(BASE, second), 2));

    assertThat(idsOf(next)).containsExactly(first);
  }

  @DisplayName("커서 다음부터 이어 읽고 앞 페이지를 다시 주지 않는다.")
  @Test
  void findMySlice_afterCursor() {
    long oldest = mine(BASE.plusMinutes(1));
    long middle = mine(BASE.plusMinutes(2));
    long newest = mine(BASE.plusMinutes(3));

    List<MyComment> next =
        commentRepository.findMySlice(query(new MyCommentCursor(BASE.plusMinutes(3), newest), 20));

    assertThat(idsOf(next)).containsExactly(middle, oldest);
  }

  private long mine(LocalDateTime createdAt) {
    return mine(createdAt, CommentStatus.ACTIVE);
  }

  private long mine(LocalDateTime createdAt, CommentStatus status) {
    return aComment().postId(postId).authorId(ME).status(status).createdAt(createdAt).insert(jdbc);
  }

  private MyCommentListQuery query(MyCommentCursor cursor, int size) {
    return new MyCommentListQuery(ME, cursor, size);
  }

  private static List<Long> idsOf(List<MyComment> found) {
    return found.stream().map(my -> my.comment().getId()).toList();
  }
}
