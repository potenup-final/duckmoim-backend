package com.duckmoim.companion.service;

import com.duckmoim.companion.domain.PostCursor;
import java.util.List;

/**
 * 모집글 목록 한 페이지 (PO-08).
 *
 * <p>{@code Slice}·{@code Page} 를 service 시그니처에 노출하지 않는다는 규칙(아키텍처-컨벤션.md 「service」)에 따라 둔 결과 객체다.
 * {@code EventSlice} · {@code CommentSlice} 와 같은 자리다.
 *
 * @param nextCursor 마지막 페이지면 null 이다
 */
public record PostSlice(List<PostView> posts, PostCursor nextCursor, boolean hasNext) {}
