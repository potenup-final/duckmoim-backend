package com.duckmoim.companion.service;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.companion.domain.UserPostListQuery;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 유저가 쓴 모집글의 <b>페이지 판정과 조립</b> (AU-09 · AU-10).
 *
 * <p>정렬 · 필터 · 커서 경계는 {@code UserPostQueryRepositoryTest} 가 본다. 여기서 보는 것은 그 위 — 한 건을 더 읽은 것을 잘라
 * 내는지, 다음 커서를 어디로 가리키는지, 댓글 수를 붙이는지다.
 */
@SpringBootTest
@Transactional
@DisplayName("유저가 쓴 모집글 조회")
class UserPostQueryServiceTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 1, 0, 0);

  private static final long ME = 2L;
  private static final long COMMENTER = 4L;

  @Autowired private UserPostQueryService userPostQueryService;
  @Autowired private JdbcTemplate jdbc;

  @DisplayName("더 읽은 한 건은 페이지에서 잘려 나가고 다음이 있다고 알린다.")
  @Test
  void findUserPosts_hasNext() {
    insertMine("첫째", BASE);
    insertMine("둘째", BASE.plusHours(1));
    insertMine("셋째", BASE.plusHours(2));

    UserPostSlice slice = userPostQueryService.findUserPosts(query(2));

    assertThat(slice.posts()).hasSize(2);
    assertThat(slice.hasNext()).isTrue();
    assertThat(slice.nextCursor()).isNotNull();
  }

  /** 다음 커서는 <b>이 페이지의 마지막 글</b>을 가리킨다. 한 건 더 읽은 쪽을 가리키면 그 글이 건너뛰어진다. */
  @DisplayName("다음 커서는 이 페이지의 마지막 모집글을 가리킨다.")
  @Test
  void findUserPosts_nextCursorPointsAtPageTail() {
    insertMine("첫째", BASE);
    long secondNewest = insertMine("둘째", BASE.plusHours(1));
    insertMine("셋째", BASE.plusHours(2));

    UserPostSlice slice = userPostQueryService.findUserPosts(query(2));

    assertThat(slice.nextCursor().id()).isEqualTo(secondNewest);
  }

  @DisplayName("다음 페이지가 없으면 커서를 주지 않는다.")
  @Test
  void findUserPosts_lastPage() {
    insertMine("하나뿐인 글", BASE);

    UserPostSlice slice = userPostQueryService.findUserPosts(query(20));

    assertThat(slice.hasNext()).isFalse();
    assertThat(slice.nextCursor()).isNull();
  }

  @DisplayName("모집글마다 댓글 수가 정확하게 붙는다.")
  @Test
  void findUserPosts_commentCount() {
    long withComments = insertMine("댓글 있는 글", BASE.plusHours(1));
    insertMine("댓글 없는 글", BASE);
    aComment().postId(withComments).authorId(COMMENTER).insert(jdbc);
    aComment().postId(withComments).authorId(COMMENTER).insert(jdbc);

    UserPostSlice slice = userPostQueryService.findUserPosts(query(20));

    assertThat(slice.posts().get(0).commentCount()).isEqualTo(2);
    assertThat(slice.posts().get(1).commentCount()).isZero();
  }

  /** 회원이 있는지 확인하지 않는다 — 없는 회원과 글이 없는 회원의 응답이 같다. 프로필 단건이 404 를 낸다. */
  @DisplayName("없는 회원번호로 물으면 빈 페이지가 온다.")
  @Test
  void findUserPosts_unknownUser() {
    insertMine("내 글", BASE);

    UserPostSlice slice =
        userPostQueryService.findUserPosts(new UserPostListQuery(9_999_999L, null, 20));

    assertThat(slice.posts()).isEmpty();
    assertThat(slice.hasNext()).isFalse();
    assertThat(slice.nextCursor()).isNull();
  }

  private long insertMine(String title, LocalDateTime createdAt) {
    return aCompanionPost().title(title).hostId(ME).createdAt(createdAt).insert(jdbc);
  }

  private static UserPostListQuery query(int size) {
    return new UserPostListQuery(ME, null, size);
  }
}
