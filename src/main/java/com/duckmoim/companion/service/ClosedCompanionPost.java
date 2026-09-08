package com.duckmoim.companion.service;

import com.duckmoim.companion.domain.ClosedReason;
import com.duckmoim.companion.domain.CompanionPost;
import com.duckmoim.companion.domain.PostStatus;

/**
 * 방금 마감한 모집글 (PO-07).
 *
 * <p><b>{@link WrittenCompanionPost} 를 쓰지 않는다.</b> 그쪽은 {@code closedReason} 을 담지 않기로 한 기록이 클래스에 남아
 * 있다 — 작성 직후에는 반드시 {@code null} 이라 알려주는 것이 없다는 이유였다. 마감은 정확히 그 필드가 결과인 경로라, 담게 고치면 작성 응답에 항상 {@code
 * null} 인 필드가 하나 생긴다.
 *
 * <p>셋만 담는 이유는 마감이 나머지를 건드리지 않기 때문이다. 제목이나 만남시각을 되돌려 주면 클라이언트가 그것을 마감의 결과로 읽는다.
 */
public record ClosedCompanionPost(Long id, PostStatus status, ClosedReason closedReason) {

  static ClosedCompanionPost from(CompanionPost post) {
    return new ClosedCompanionPost(post.getId(), post.getStatus(), post.getClosedReason());
  }
}
