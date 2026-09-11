package com.duckmoim.companion.service;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.domain.Comment;
import com.duckmoim.companion.domain.CommentCursor;
import com.duckmoim.companion.domain.CommentListQuery;
import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.domain.CompanionPost;
import com.duckmoim.companion.exception.PostErrorCode;
import com.duckmoim.companion.infra.AuthoredComment;
import com.duckmoim.companion.infra.CommentRepository;
import com.duckmoim.companion.infra.CompanionPostRepository;
import com.duckmoim.identity.domain.AuthorDisplay;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 댓글 목록 조회 (CM-06 · CM-07 · CM-11).
 *
 * <p><b>본문을 보여줄지는 여기서 정하지 않는다.</b> 그 판정과 응답 조립은 presentation 의 조립기가 한다 (도메인-모델링.md 「7.1 가시성과 권한」).
 * 이 서비스는 무엇을 읽고 무엇을 목록에 남길지까지만 본다.
 */
@Service
@RequiredArgsConstructor
public class CommentQueryService {

  private final CommentRepository commentRepository;
  private final CompanionPostRepository companionPostRepository;
  private final Clock clock;

  /**
   * 한 모집글의 댓글을 한 페이지 읽는다.
   *
   * <p>모집글이 없으면 404 다. 마감된 글의 댓글도 읽힌다 — 도메인 6장이 {@code CLOSED} 의 열람을 「가능」으로 정했다.
   */
  @Transactional(readOnly = true)
  public CommentSlice findComments(CommentListQuery query) {
    CompanionPost post = requirePost(query.postId());

    List<AuthoredComment> read = commentRepository.findRootSlice(query);
    boolean hasNext = read.size() > query.size();
    List<AuthoredComment> roots = hasNext ? read.subList(0, query.size()) : read;

    return new CommentSlice(
        withReplies(query.postId(), roots), nextCursor(roots, hasNext), hasNext, post.getHostId());
  }

  /** 방장이 누구인지가 본문 열람 판정의 입력이라 존재 확인만으로 끝나지 않는다 (도메인 7.1). */
  private CompanionPost requirePost(Long postId) {
    return companionPostRepository
        .findById(postId)
        .orElseThrow(() -> new BusinessException(PostErrorCode.POST_NOT_FOUND));
  }

  private List<CommentView> withReplies(Long postId, List<AuthoredComment> roots) {
    Map<Long, List<AuthoredComment>> byParent = repliesByParent(postId, roots);

    return roots.stream()
        .map(root -> toView(root, visible(byParent.getOrDefault(idOf(root), List.of()))))
        .filter(CommentQueryService::staysInList)
        .toList();
  }

  private Map<Long, List<AuthoredComment>> repliesByParent(
      Long postId, List<AuthoredComment> roots) {

    List<Long> rootIds = roots.stream().map(CommentQueryService::idOf).toList();

    return commentRepository.findRepliesOf(postId, rootIds).stream()
        .collect(
            Collectors.groupingBy(
                replied -> replied.comment().getParentId(),
                LinkedHashMap::new,
                Collectors.toList()));
  }

  private List<CommentView> visible(List<AuthoredComment> replies) {
    return replies.stream()
        .map(reply -> toView(reply, List.of()))
        .filter(CommentQueryService::staysInList)
        .toList();
  }

  /**
   * 목록에 남을지 판정한다 (CM-11).
   *
   * <p>API-설계.md 「2-5. 댓글 (Companion)」이 <i>"하위 대댓글이 있으면 목록에 남고, 없으면 목록에서 빠진다"</i> 고 정했다. 살아 있는 댓글은
   * 언제나 남고, 자리표시자는 <b>매달린 대댓글이 있을 때만</b> 남는다 — 그래야 대댓글이 고아가 되지 않는다.
   *
   * <p>두 가지가 문서에 없어서 정했다. <b>첫째, 대댓글은 자기 대댓글을 가질 수 없으므로(I-06) 지워진 대댓글은 항상 목록에서 빠진다.</b> 둘째, <b>남은
   * 대댓글이 하나도 없으면 루트도 빠진다</b> — 모두 지워진 뒤의 루트를 남기면 아무것도 매달리지 않은 「삭제된 댓글입니다」만 뜬다.
   *
   * <p>{@code BLINDED} 도 같게 본다. 위 문서가 {@code DELETED} 와 나란히 적었다.
   */
  private static boolean staysInList(CommentView view) {
    return view.comment().getStatus() == CommentStatus.ACTIVE || !view.replies().isEmpty();
  }

  /** 작성자 값은 {@link AuthorDisplay} 를 지난 것만 싣는다. 탈퇴한 작성자는 여기서 자리표시자가 된다 (AU-11). */
  private CommentView toView(AuthoredComment authored, List<CommentView> replies) {
    AuthorDisplay author = authored.author(clock);

    return new CommentView(
        authored.comment(),
        author.nickname(),
        author.profileImageUrl(),
        author.lastSeen(),
        replies);
  }

  /**
   * 다음 페이지의 시작점.
   *
   * <p><b>목록에서 걸러지기 전의 마지막 루트를 가리킨다.</b> CM-11 로 빠진 댓글도 커서 위치로는 세어야 한다 — 걸러진 뒤를 기준으로 삼으면 그 댓글을 다음
   * 페이지가 다시 읽어 무한히 같은 자리를 맴돈다. 그래서 한 페이지가 {@code size} 보다 적게 나올 수 있고, 클라이언트는 개수가 아니라 {@code
   * hasNext} 를 본다.
   */
  private static CommentCursor nextCursor(List<AuthoredComment> roots, boolean hasNext) {
    if (!hasNext) {
      return null;
    }

    Comment last = roots.get(roots.size() - 1).comment();
    return new CommentCursor(last.getCreatedAt(), last.getId());
  }

  private static Long idOf(AuthoredComment authored) {
    return authored.comment().getId();
  }
}
