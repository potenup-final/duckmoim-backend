package com.duckmoim.companion.service;

import com.duckmoim.companion.domain.MyCommentCursor;
import java.util.List;

/**
 * 내 댓글 내역 한 페이지 (CM-16).
 *
 * <p>{@code Slice}·{@code Page} 를 service 시그니처에 노출하지 않는다는 규칙(아키텍처-컨벤션.md 「service」)에 따라 둔 결과 객체다.
 * {@link CommentSlice} 와 같은 자리다.
 *
 * <p><b>{@code hostId} 가 없다.</b> 목록 쪽 결과 객체는 방장을 함께 올린다 — 방장이 남의 비밀 댓글을 볼 수 있는 유일한 일반 유저이기 때문이다
 * (도메인-모델링.md 「7.1 가시성과 권한」). 내 내역에서는 요청자가 곧 작성자라 그 입력이 판정에 쓰이지 않는다.
 *
 * @param nextCursor 마지막 페이지면 null 이다
 */
public record MyCommentSlice(
    List<MyCommentView> items, MyCommentCursor nextCursor, boolean hasNext) {}
