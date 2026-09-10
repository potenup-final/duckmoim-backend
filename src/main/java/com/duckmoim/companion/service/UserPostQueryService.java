package com.duckmoim.companion.service;

import com.duckmoim.companion.domain.Capacity;
import com.duckmoim.companion.domain.CompanionPost;
import com.duckmoim.companion.domain.UserPostCursor;
import com.duckmoim.companion.domain.UserPostListQuery;
import com.duckmoim.companion.infra.AuthoredPost;
import com.duckmoim.companion.infra.CommentRepository;
import com.duckmoim.companion.infra.CompanionPostRepository;
import com.duckmoim.identity.domain.AuthorDisplay;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 유저가 쓴 모집글 조회 (AU-09 · AU-10).
 *
 * <p><b>회원이 있는지 확인하지 않는다.</b> 없는 회원번호로 물으면 <b>200 과 빈 페이지</b>가 나간다 — 404 를 내려면 회원 조회가 한 번 더 붙는데,
 * <b>없는 회원과 글이 없는 회원의 응답이 어차피 같아</b> 존재 여부가 새지 않는다. 프로필 단건({@code GET /users/&#123;userId&#125;})이
 * 404 를 내므로 화면은 그쪽으로 판단한다.
 *
 * <p><b>{@code CompanionPostQueryService} 와 갈랐다.</b> 그쪽은 만남시각 임박순 목록과 단건이고 이쪽은 작성 최신순이다. 정렬 · 커서 ·
 * 인덱스가 전부 다르므로 한 서비스에 두면 어느 메서드가 어느 커서를 쓰는지가 이름에서 사라진다.
 *
 * <p><b>댓글 수 집계는 그쪽과 같은 것을 쓴다.</b> 페이지 전체를 한 번에 센다 (CM-12) — 모집글마다 부르면 20건이면 쿼리가 20개다.
 */
@Service
@RequiredArgsConstructor
public class UserPostQueryService {

  private final CompanionPostRepository companionPostRepository;
  private final CommentRepository commentRepository;
  private final Clock clock;

  @Transactional(readOnly = true)
  public UserPostSlice findUserPosts(UserPostListQuery query) {
    List<AuthoredPost> read = companionPostRepository.findUserPostSlice(query);

    boolean hasNext = read.size() > query.size();
    List<AuthoredPost> page = hasNext ? read.subList(0, query.size()) : read;

    Map<Long, Long> commentCounts = commentCountsOf(page);

    return new UserPostSlice(
        page.stream().map(authored -> toView(authored, commentCounts)).toList(),
        nextCursor(page, hasNext),
        hasNext);
  }

  private Map<Long, Long> commentCountsOf(List<AuthoredPost> page) {
    return commentRepository.countActiveByPostIds(
        page.stream().map(authored -> authored.post().getId()).toList());
  }

  /**
   * 카드 모양을 {@code CompanionPostQueryService} 와 똑같이 만든다.
   *
   * <p>같은 {@link PostView} 를 채우므로 프론트가 모집글 카드 파서를 하나만 쓴다. 화면 계약 3장의 {@code district} 는 넣지 않는다 —
   * 목록(PO-08)에도 없고, 도메인 5장 「확인이 남은 것」에 <i>"스냅샷 대상이 둘뿐인데 조인으로 얻어야 하는 자리"</i> 로 올라가 있다.
   */
  private PostView toView(AuthoredPost authored, Map<Long, Long> commentCounts) {
    CompanionPost post = authored.post();
    AuthorDisplay host =
        AuthorDisplay.of(
            authored.hostStatus(),
            authored.nickname(),
            authored.profileImageUrl(),
            authored.lastSeenAt(),
            clock);

    return new PostView(
        post.getId(),
        authored.eventExternalId(),
        post.getEventTitle(),
        post.getEventImageUrl(),
        post.getTitle(),
        post.getContent(),
        post.getStatus(),
        post.getClosedReason(),
        Capacity.valueOf(post.getCapacity()),
        post.getMeetAt(),
        post.getCreatedAt(),
        post.getMeetPoint(),
        post.getHostId(),
        host.nickname(),
        host.profileImageUrl(),
        host.lastSeen(),
        // 댓글이 없는 모집글은 집계에 아예 없다. GROUP BY 는 행이 없는 그룹을 만들지 않는다
        commentCounts.getOrDefault(post.getId(), 0L));
  }

  /** 다음 페이지의 시작점. 이 페이지의 마지막 모집글을 가리킨다. */
  private static UserPostCursor nextCursor(List<AuthoredPost> page, boolean hasNext) {
    if (!hasNext) {
      return null;
    }

    CompanionPost last = page.get(page.size() - 1).post();
    return new UserPostCursor(last.getCreatedAt(), last.getId());
  }
}
