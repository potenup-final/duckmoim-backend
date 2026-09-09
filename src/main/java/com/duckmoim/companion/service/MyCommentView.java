package com.duckmoim.companion.service;

import com.duckmoim.companion.domain.Comment;

/**
 * 내 내역에 실리는 댓글 한 건과 그것이 달린 모집글의 제목 (CM-16).
 *
 * <p>저장소의 조인 결과({@code MyComment})를 그대로 올리지 않는다 — {@code presentation} 은 {@code infra} 를 참조하지 못한다
 * (게이트 {@code LAYER_DEPENDENCY}). {@link CommentView} 가 같은 이유로 있다.
 *
 * <p>{@code Comment} 자체는 domain 이라 presentation 도 볼 수 있다. 본문을 보여줄지 판정하려면 그 엔티티가 필요하다.
 *
 * <p><b>작성자 블록이 없다.</b> 내 내역의 작성자는 언제나 요청자 자신이라 닉네임과 아바타를 다시 내릴 이유가 없다 (화면-계약.md 의 내 내역 응답).
 */
public record MyCommentView(Comment comment, String postTitle) {}
