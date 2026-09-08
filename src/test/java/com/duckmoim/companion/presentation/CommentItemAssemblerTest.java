package com.duckmoim.companion.presentation;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.companion.domain.Comment;
import com.duckmoim.companion.domain.CommentReadContext;
import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.domain.CommentVisibilityPolicy;
import com.duckmoim.companion.infra.CommentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판정 결과가 응답에 반영되는지 본다 (CM-05 · CM-08 · CM-20).
 *
 * <p>판정 자체의 전 조합은 {@code CommentVisibilityPolicyTest} 가 60개로 덮고, JSON 키가 사라지는지는 {@link
 * CommentItemResponseTest} 가 본다. 여기서는 <b>둘을 잇는 배선</b>만 본다 — 조립기가 판정을 부르는지, 결과를 어느 필드에 반영하는지.
 *
 * <p><b>단위 테스트가 아닌 이유.</b> 실제 {@code Comment} 가 필요하다. {@code DELETED} · {@code BLINDED} 로 만드는 경로가
 * 아직 없고(삭제는 CM-10, 블라인드는 신고 처리), {@code createdAt} 은 저장 시점에 생긴다. 테스트를 위해 도메인에 생성 경로를 뚫는 것은 프로덕션이 쓰지
 * 않는 문을 만드는 일이라, {@code EventFixture} 와 같이 SQL 로 넣는다.
 */
@SpringBootTest
@Transactional
class CommentItemAssemblerTest {

  private static final long AUTHOR_ID = 11L;
  private static final long HOST_ID = 12L;
  private static final long PARENT_AUTHOR_ID = 13L;
  private static final long STRANGER_ID = 14L;

  private static final CommentAuthorResponse AUTHOR_BLOCK =
      new CommentAuthorResponse(AUTHOR_ID, "밤샘예매", "/avatar/a2.webp");

  private final CommentItemAssembler assembler =
      new CommentItemAssembler(new CommentVisibilityPolicy());

  @Autowired private CommentRepository commentRepository;
  @Autowired private JdbcTemplate jdbc;

  private long postId;

  @BeforeEach
  void setUp() {
    postId = aCompanionPost().insert(jdbc);
  }

  @DisplayName("공개 댓글은 비회원 요청에도 본문이 담긴다.")
  @Test
  void assemblePublicCommentForGuest() {
    Comment comment = saved(aComment().postId(postId).authorId(AUTHOR_ID).secret(false));

    CommentItemResponse response =
        assembler.assemble(comment, AUTHOR_BLOCK, CommentReadContext.ofGuest(HOST_ID, null));

    assertThat(response.content()).isEqualTo(comment.getContent());
  }

  @DisplayName("비밀 루트 댓글은 방장에게 본문이 담긴다.")
  @Test
  void assembleSecretRootForHost() {
    Comment comment = saved(aComment().postId(postId).authorId(AUTHOR_ID).secret(true));

    CommentItemResponse response =
        assembler.assemble(comment, AUTHOR_BLOCK, new CommentReadContext(HOST_ID, HOST_ID, null));

    assertThat(response.content()).isEqualTo(comment.getContent());
  }

  @DisplayName("비밀 루트 댓글은 제3자에게 본문이 담기지 않는다.")
  @Test
  void assembleSecretRootForStranger() {
    Comment comment = saved(aComment().postId(postId).authorId(AUTHOR_ID).secret(true));

    CommentItemResponse response =
        assembler.assemble(
            comment, AUTHOR_BLOCK, new CommentReadContext(STRANGER_ID, HOST_ID, null));

    assertThat(response.content()).isNull();
  }

  @DisplayName("비밀 대댓글은 부모 댓글 작성자에게 본문이 담긴다.")
  @Test
  void assembleSecretReplyForParentAuthor() {
    long parentId = aComment().postId(postId).authorId(PARENT_AUTHOR_ID).insert(jdbc);
    Comment reply =
        saved(aComment().postId(postId).authorId(AUTHOR_ID).parentId(parentId).secret(true));

    CommentItemResponse response =
        assembler.assemble(
            reply,
            AUTHOR_BLOCK,
            new CommentReadContext(PARENT_AUTHOR_ID, HOST_ID, PARENT_AUTHOR_ID));

    assertThat(response.content()).isEqualTo(reply.getContent());
  }

  /** 본문만 사라진다. 자리표시자도 아바타 · 닉네임 · 작성시각을 그대로 내린다 (CM-08). */
  @DisplayName("자리표시자에도 작성자와 작성시각은 그대로 담긴다.")
  @Test
  void assemblePlaceholderKeepsMetadata() {
    Comment comment = saved(aComment().postId(postId).authorId(AUTHOR_ID).secret(true));

    CommentItemResponse response =
        assembler.assemble(
            comment, AUTHOR_BLOCK, new CommentReadContext(STRANGER_ID, HOST_ID, null));

    assertThat(response.content()).isNull();
    assertThat(response.author()).isEqualTo(AUTHOR_BLOCK);
    assertThat(response.createdAt()).isNotNull();
    assertThat(response.secret()).isTrue();
    assertThat(response.status()).isEqualTo(CommentStatus.ACTIVE);
  }

  /** 작성자 본인도 못 본다. 7.1 「삭제 · 블라인드 댓글 — 없음(자리표시자만)」 행이다. */
  @DisplayName("삭제되거나 블라인드된 댓글은 작성자에게도 본문이 담기지 않는다.")
  @ParameterizedTest
  @EnumSource(
      value = CommentStatus.class,
      names = {"DELETED", "BLINDED"})
  void assembleInactiveForAuthor(CommentStatus status) {
    Comment comment =
        saved(aComment().postId(postId).authorId(AUTHOR_ID).secret(false).status(status));

    CommentItemResponse response =
        assembler.assemble(comment, AUTHOR_BLOCK, new CommentReadContext(AUTHOR_ID, HOST_ID, null));

    assertThat(response.content()).isNull();
    assertThat(response.status()).isEqualTo(status);
  }

  private Comment saved(com.duckmoim.companion.CommentFixture fixture) {
    return commentRepository.findById(fixture.insert(jdbc)).orElseThrow();
  }
}
