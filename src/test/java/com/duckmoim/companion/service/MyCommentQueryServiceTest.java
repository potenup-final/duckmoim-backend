package com.duckmoim.companion.service;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

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
 * 한 페이지를 어디서 끊고 다음 커서를 어디에 두는지 (CM-16).
 *
 * <p>정렬과 필터는 {@code MyCommentQueryRepositoryTest} 가 본다. 여기서는 <b>페이지 경계와 커서</b>만 본다.
 *
 * <p>작성자는 V11 시드의 2 를 쓴다.
 */
@SpringBootTest
@Transactional
class MyCommentQueryServiceTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 1, 0, 0);

  private static final long ME = 2L;

  @Autowired private MyCommentQueryService myCommentQueryService;
  @Autowired private JdbcTemplate jdbc;

  private long postId;

  @BeforeEach
  void setUp() {
    postId = aCompanionPost().title("에이티즈 팝업 오픈런 같이 하실 분").insert(jdbc);
  }

  @DisplayName("내 댓글에 모집글 제목이 함께 실린다.")
  @Test
  void findMyComments() {
    mine(BASE);

    MyCommentSlice slice = myCommentQueryService.findMyComments(query(null, 20));

    assertThat(slice.items()).hasSize(1);
    assertThat(slice.items().get(0).postTitle()).isEqualTo("에이티즈 팝업 오픈런 같이 하실 분");
  }

  /** 한 건을 더 읽어 판정하므로, 더 읽은 그 건은 페이지에 실리지 않는다. */
  @DisplayName("다음 페이지가 있으면 요청한 크기만큼만 실린다.")
  @Test
  void findMyComments_hasNext() {
    mine(BASE.plusMinutes(1));
    long middle = mine(BASE.plusMinutes(2));
    long newest = mine(BASE.plusMinutes(3));

    MyCommentSlice slice = myCommentQueryService.findMyComments(query(null, 2));

    assertThat(idsOf(slice)).containsExactly(newest, middle);
    assertThat(slice.hasNext()).isTrue();
    assertThat(slice.nextCursor()).isEqualTo(new MyCommentCursor(BASE.plusMinutes(2), middle));
  }

  @DisplayName("마지막 페이지에서는 다음 커서가 없다.")
  @Test
  void findMyComments_isLastPage() {
    mine(BASE.plusMinutes(1));
    mine(BASE.plusMinutes(2));

    MyCommentSlice slice = myCommentQueryService.findMyComments(query(null, 20));

    assertThat(slice.hasNext()).isFalse();
    assertThat(slice.nextCursor()).isNull();
  }

  @DisplayName("다음 커서로 이어 읽으면 남은 것이 온다.")
  @Test
  void findMyComments_readsNextPage() {
    long oldest = mine(BASE.plusMinutes(1));
    mine(BASE.plusMinutes(2));
    mine(BASE.plusMinutes(3));

    MyCommentSlice first = myCommentQueryService.findMyComments(query(null, 2));
    MyCommentSlice second = myCommentQueryService.findMyComments(query(first.nextCursor(), 2));

    assertThat(idsOf(second)).containsExactly(oldest);
    assertThat(second.hasNext()).isFalse();
  }

  @DisplayName("쓴 댓글이 없으면 빈 페이지다.")
  @Test
  void findMyComments_isEmpty() {
    MyCommentSlice slice = myCommentQueryService.findMyComments(query(null, 20));

    assertThat(slice.items()).isEmpty();
    assertThat(slice.hasNext()).isFalse();
    assertThat(slice.nextCursor()).isNull();
  }

  private long mine(LocalDateTime createdAt) {
    return aComment().postId(postId).authorId(ME).createdAt(createdAt).insert(jdbc);
  }

  private static MyCommentListQuery query(MyCommentCursor cursor, int size) {
    return new MyCommentListQuery(ME, cursor, size);
  }

  private static List<Long> idsOf(MyCommentSlice slice) {
    return slice.items().stream().map(item -> item.comment().getId()).toList();
  }
}
