package com.duckmoim.companion.service;

import com.duckmoim.companion.domain.Comment;
import com.duckmoim.identity.domain.LastSeen;

/**
 * 관리자가 열어 본 댓글 한 건 (CM-17).
 *
 * <p><b>{@link CommentView} 와 따로 두는 이유는 {@code replies} 다.</b> 그쪽은 목록용이라 대댓글을 안고 있는데, 여기는 한 건 조회라
 * 채울 것이 없다. 돌려 쓰면 언제나 빈 리스트인 필드가 응답 조립까지 따라가고, 그것을 본 다음 사람이 「대댓글도 주는 경로」로 읽는다.
 *
 * <p>본문을 감추는 분기가 없다. <b>관리자는 가시성 매트릭스 밖이다</b> (도메인-모델링.md 「7. 도메인 규칙」) — 감출 수 있으면 이 경로가 있을 이유가 없다.
 *
 * @param lastSeen 구간 값. 원본 시각은 여기까지 오지 않는다 (도메인-모델링.md 「7.2 최근 접속일 노출」)
 */
public record AdminCommentView(
    Comment comment, String nickname, String profileImageUrl, LastSeen lastSeen) {}
