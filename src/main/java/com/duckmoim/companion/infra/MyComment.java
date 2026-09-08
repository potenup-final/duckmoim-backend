package com.duckmoim.companion.infra;

import com.duckmoim.companion.domain.Comment;

/**
 * 내 댓글 한 건과 그 댓글이 달린 모집글의 제목을 함께 읽은 결과 (CM-16).
 *
 * <p>{@code Comment} 는 모집글을 {@code postId} 로만 참조한다 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」). 그런데 내 내역 응답에는
 * <b>어느 모집글에 쓴 댓글인지</b>가 필요해서 조회 시점에 조인한다 — 같은 문서가 그 경우를 이미 허용했다: <i>"이 규칙은 쓰기 모델의 것이다. 조회 전용 쿼리는
 * 조인해도 된다."</i>
 *
 * <p>{@link AuthoredComment} 와 조인 대상이 다르다. 목록은 <b>작성자</b>를 붙이고 (닉네임 · 아바타), 내 내역은 <b>모집글</b>을 붙인다 —
 * 작성자가 언제나 나 자신이라 닉네임과 아바타를 다시 내릴 이유가 없다 (화면-계약.md 의 내 내역 응답에 {@code author} 블록이 없다).
 *
 * <p>모집글에서 가져오는 것은 제목 하나다. 나머지 필드는 CM-16 이 요구하는 항목(모집글 제목 · 본문 · 작성 시각) 밖이다.
 */
public record MyComment(Comment comment, String postTitle) {}
