package com.duckmoim.companion.presentation;

import com.duckmoim.companion.service.CommentView;
import com.duckmoim.identity.domain.LastSeen;

/**
 * 댓글 작성자 블록.
 *
 * <p><b>API-설계.md 「2-5. 댓글 (Companion)」이 정한 넷이다</b> — {@code id} · {@code nickname} · {@code
 * profileImageUrl} · {@code lastSeen}. 모집글 응답의 {@code author} 도 같은 모양이다.
 *
 * <p>{@code lastSeen} 은 구간 값이고 <b>원본 시각은 어느 경로에도 나가지 않는다</b> (도메인-모델링.md 「7.2 최근 접속일 노출」). 관측된 적이
 * 없으면 null 이다.
 *
 * <p>자리표시자에서도 이 블록은 그대로 내려간다 (CM-08). 가려지는 것은 본문뿐이다.
 */
public record CommentAuthorResponse(
    Long id, String nickname, String profileImageUrl, LastSeen lastSeen) {

  static CommentAuthorResponse from(CommentView view) {
    return new CommentAuthorResponse(
        view.comment().getAuthorId(), view.nickname(), view.profileImageUrl(), view.lastSeen());
  }
}
