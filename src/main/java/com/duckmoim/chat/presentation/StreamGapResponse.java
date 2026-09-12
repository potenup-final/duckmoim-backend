package com.duckmoim.chat.presentation;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 되돌려주기에 너무 많이 밀렸다는 신호 (CH-11).
 *
 * <p><b>말풍선이 아니다.</b> 선로에 {@code event: gap} 으로 실리고, 클라이언트는 이것을 받으면 메시지 목록 API(CH-09)로 따라잡는다 — 그쪽이
 * 이미 커서 페이징이라 새로 만들 것이 없다.
 *
 * <p><b>이 사건에는 {@code id:} 줄이 없다.</b> 실으면 브라우저의 재개 지점이 앞으로 밀려 다음 재연결이 빠진 구간을 건너뛴다 — <b>구멍이 굳는다.</b>
 *
 * @param fromMessageId 클라이언트가 마지막으로 받았다고 알려 온 번호. 목록을 이 번호까지 거슬러 올라가면 구간이 메워진다
 */
public record StreamGapResponse(
    @Schema(description = "이 번호 뒤가 비어 있다. 목록 API 로 따라잡는다", example = "101") Long fromMessageId) {}
