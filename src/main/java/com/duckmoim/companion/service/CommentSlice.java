package com.duckmoim.companion.service;

import com.duckmoim.companion.domain.CommentCursor;
import java.util.List;

/**
 * 댓글 목록 한 페이지 (CM-07).
 *
 * <p>{@code Slice}·{@code Page} 를 service 시그니처에 노출하지 않는다는 규칙(아키텍처-컨벤션.md 「service」)에 따라 둔 결과 객체다.
 * {@code EventSlice} 와 같은 자리다.
 *
 * @param roots 루트 댓글. 각자 자기 대댓글을 안에 달고 있다
 * @param nextCursor 마지막 페이지면 null 이다
 * @param hostId 이 모집글의 방장. <b>본문 열람 판정의 입력이라 함께 올린다</b> — 방장은 남의 비밀 댓글을 볼 수 있는 유일한 일반 유저다
 *     (도메인-모델링.md 「7.1 가시성과 권한」). 없으면 presentation 이 모집글을 다시 읽어야 한다
 */
public record CommentSlice(
    List<CommentView> roots, CommentCursor nextCursor, boolean hasNext, Long hostId) {}
