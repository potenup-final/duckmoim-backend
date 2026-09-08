package com.duckmoim.companion.infra;

import com.duckmoim.companion.domain.UserPostListQuery;
import java.util.List;

/**
 * 소유자 기준 모집글 조회 (AU-09 · AU-10).
 *
 * <p><b>{@link CompanionPostQueryRepository} 에 넣지 않고 갈랐다.</b> 그쪽은 만남시각 임박순 목록(PO-08)과 단건이고 이쪽은 작성
 * 최신순이다. 정렬과 커서가 다르므로 한 인터페이스에 두면 <b>어느 메서드가 어느 커서를 쓰는지</b>가 이름에서 사라진다. {@code
 * MyCommentQueryRepository} 를 {@code CommentQueryRepository} 와 갈라 둔 것과 같은 판단이다.
 */
public interface UserPostQueryRepository {

  List<AuthoredPost> findUserPostSlice(UserPostListQuery query);
}
