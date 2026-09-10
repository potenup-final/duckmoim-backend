package com.duckmoim.companion.service;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.domain.Capacity;
import com.duckmoim.companion.domain.CompanionPost;
import com.duckmoim.companion.domain.PostCursor;
import com.duckmoim.companion.domain.PostListQuery;
import com.duckmoim.companion.exception.PostErrorCode;
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
 * 모집글 조회 — 목록(PO-08)과 상세(PO-11).
 *
 * <p><b>작성과 나눈 이유.</b> 아키텍처 컨벤션이 service 를 나누는 기준으로 「하나의 유즈케이스」를 들었고, 여기가 쓰는 것은 조회 전용 저장소와 시계뿐이다
 * ({@code EventQueryService} 가 같은 자리).
 *
 * <p><b>읽기 전용 트랜잭션 안에서 결과 객체까지 만들어 내보낸다.</b> {@code open-in-view: false} 라 트랜잭션 밖에서 엔티티를 건드리면 그
 * 자리에서 실패하고, 그것이 엔티티가 presentation 으로 새는 것을 막는 두 번째 방어다.
 */
@Service
@RequiredArgsConstructor
public class CompanionPostQueryService {

  private final CompanionPostRepository companionPostRepository;
  private final CommentRepository commentRepository;
  private final Clock clock;

  /**
   * 조건에 맞는 모집글 한 페이지를 읽는다.
   *
   * <p>한 건을 더 읽어 다음 페이지 유무를 판정하고, 그 한 건은 응답에서 잘라낸다. 총 건수를 세지 않는다 (API 컨벤션은 items · nextCursor ·
   * hasNext 셋만 쓴다).
   */
  @Transactional(readOnly = true)
  public PostSlice findPosts(PostListQuery query) {
    List<AuthoredPost> read = companionPostRepository.findSlice(query);
    boolean hasNext = read.size() > query.size();
    List<AuthoredPost> page = hasNext ? read.subList(0, query.size()) : read;

    Map<Long, Long> commentCounts = commentCountsOf(page);

    return new PostSlice(
        page.stream().map(authored -> toView(authored, commentCounts)).toList(),
        nextCursor(page, hasNext),
        hasNext);
  }

  /**
   * 모집글 한 건을 읽는다 (PO-11).
   *
   * <p><b>요청자를 받지 않는다.</b> API-설계.md 「2-4. 모집글 (Companion)」이 <i>"비인증 요청에도 본문 포함 200"</i> 으로 정했다 —
   * 모집글 본문은 도메인-모델링.md 「7.1 가시성과 권한」의 표에서 「비회원 및 회원 열람 가능」이고, 가리는 것은 비밀 댓글 본문뿐이다. 요청자를 인자로 두면 쓰지 않는
   * 판정 입력이 생기고 그것이 나중에 판정처럼 읽힌다.
   *
   * <p><b>마감된 글도 준다.</b> 도메인 6장이 {@code CLOSED} 의 열람을 「가능」으로 정했다.
   *
   * <p>없으면 {@code POST_NOT_FOUND} 404 다. 모집글에 삭제가 없으므로 (결정 D-3) 「지워져서 없는 글」과 「원래 없는 글」이 갈리지 않는다.
   */
  @Transactional(readOnly = true)
  public PostView findPost(Long postId) {
    AuthoredPost authored =
        companionPostRepository
            .findAuthored(postId)
            .orElseThrow(() -> new BusinessException(PostErrorCode.POST_NOT_FOUND));

    return toView(authored, commentCountsOf(List.of(authored)));
  }

  /** 페이지 전체를 한 번에 센다 (CM-12). 모집글마다 부르면 20건이면 쿼리가 20개다. */
  private Map<Long, Long> commentCountsOf(List<AuthoredPost> page) {
    return commentRepository.countActiveByPostIds(
        page.stream().map(authored -> authored.post().getId()).toList());
  }

  /** 방장 값은 {@link AuthorDisplay} 를 지난 것만 싣는다. 탈퇴한 방장은 여기서 자리표시자가 된다 (AU-11). */
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

  /**
   * 다음 페이지의 시작점.
   *
   * <p>정렬 키를 그대로 담는다 — 커서 키가 정렬 키와 어긋나면 페이지 경계에서 누락이 생기고, PO-08 의 검증 기준이 그것이다.
   */
  private static PostCursor nextCursor(List<AuthoredPost> page, boolean hasNext) {
    if (!hasNext) {
      return null;
    }

    CompanionPost last = page.get(page.size() - 1).post();
    return new PostCursor(last.getMeetAt(), last.getId());
  }
}
