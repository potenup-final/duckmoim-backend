package com.duckmoim.companion.presentation;

import com.duckmoim.companion.service.PostView;
import com.duckmoim.identity.domain.LastSeen;

/**
 * 방장 블록.
 *
 * <p><b>API-설계.md 「2-5. 댓글 (Companion)」이 정한 넷이다</b> — {@code id} · {@code nickname} · {@code
 * profileImageUrl} · {@code lastSeen}. 같은 절이 <i>"모집글 응답의 {@code author} 도 같은 모양이다"</i> 라고 적었다.
 *
 * <p>{@code lastSeen} 은 구간 값이고 <b>원본 시각은 어느 경로에도 나가지 않는다</b> (도메인-모델링.md 「7.2 최근 접속일 노출」). 관측된 적이
 * 없으면 null 이다.
 *
 * <p>{@code CommentAuthorResponse} 와 필드가 같지만 합치지 않았다. 한쪽은 {@code CommentView} 에서, 한쪽은 {@link
 * PostView} 에서 조립하는데 두 조립기를 한 레코드에 넣으면 그 레코드가 양쪽 결과 객체를 모두 알아야 한다.
 */
public record PostAuthorResponse(
    Long id, String nickname, String profileImageUrl, LastSeen lastSeen) {

  static PostAuthorResponse from(PostView view) {
    return new PostAuthorResponse(
        view.hostId(), view.nickname(), view.profileImageUrl(), view.lastSeen());
  }
}
