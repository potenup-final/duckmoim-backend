package com.duckmoim.companion.service;

import com.duckmoim.companion.domain.UserPostCursor;
import java.util.List;

/**
 * 유저가 쓴 모집글 한 페이지 (AU-09 · AU-10).
 *
 * <p><b>{@link PostSlice} 와 항목 타입은 같고 커서 타입만 다르다.</b> 카드가 같은 모양이어야 프론트가 파서를 하나만 쓰고, 커서는 정렬 키가 달라
 * 섞이면 안 된다 ({@link UserPostCursor}).
 */
public record UserPostSlice(List<PostView> posts, UserPostCursor nextCursor, boolean hasNext) {}
