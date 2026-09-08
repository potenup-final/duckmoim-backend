package com.duckmoim.companion.service;

import com.duckmoim.companion.domain.Comment;
import com.duckmoim.companion.domain.MyCommentCursor;
import com.duckmoim.companion.domain.MyCommentListQuery;
import com.duckmoim.companion.infra.CommentRepository;
import com.duckmoim.companion.infra.MyComment;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 댓글 내역 조회 (CM-16 · AU-10).
 *
 * <p><b>본문을 보여줄지는 여기서 정하지 않는다.</b> 그 판정과 응답 조립은 presentation 의 조립기가 한다 (도메인-모델링.md 「7.1 가시성과 권한」).
 * {@link CommentQueryService} 와 같은 분담이다.
 *
 * <p><b>모집글 존재를 확인하지 않는다.</b> 목록 조회는 방장이 판정 입력이라 모집글을 따로 읽어야 하지만, 여기서는 댓글이 이미 모집글과 조인되어 나온다 — 조인이 안
 * 되는 댓글은 애초에 결과에 없다.
 */
@Service
@RequiredArgsConstructor
public class MyCommentQueryService {

  private final CommentRepository commentRepository;

  /**
   * 내가 쓴 댓글을 한 페이지 읽는다.
   *
   * <p>거를 것이 없어 읽은 것이 그대로 한 페이지다. 목록 쪽이 CM-11 로 자리표시자를 빼면서 「걸러지기 전의 마지막」을 커서로 삼아야 했던 것과 갈리는 지점이다 —
   * 삭제·블라인드는 이미 SQL 이 뺐다 ({@link MyCommentListQuery}).
   */
  @Transactional(readOnly = true)
  public MyCommentSlice findMyComments(MyCommentListQuery query) {
    List<MyComment> read = commentRepository.findMySlice(query);

    boolean hasNext = read.size() > query.size();
    List<MyComment> page = hasNext ? read.subList(0, query.size()) : read;

    return new MyCommentSlice(views(page), nextCursor(page, hasNext), hasNext);
  }

  private static List<MyCommentView> views(List<MyComment> page) {
    return page.stream().map(my -> new MyCommentView(my.comment(), my.postTitle())).toList();
  }

  /** 다음 페이지의 시작점. 이 페이지의 마지막 댓글을 가리킨다. */
  private static MyCommentCursor nextCursor(List<MyComment> page, boolean hasNext) {
    if (!hasNext) {
      return null;
    }

    Comment last = page.get(page.size() - 1).comment();
    return new MyCommentCursor(last.getCreatedAt(), last.getId());
  }
}
