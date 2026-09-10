package com.duckmoim.chat.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 초대 요청 (CH-02).
 *
 * <p><b>댓글 번호가 아니라 회원번호를 받는다.</b> 화면의 진입점은 댓글이지만 (CM-01) 검증 기준이 <i>"댓글을 쓰지 않은 유저 초대 시 400"</i> 이라
 * 대상의 단위가 사람이다. 댓글 번호로 받으면 같은 사람이 댓글 셋을 썼을 때 셋 중 어느 것으로 부르든 결과가 같아야 하는 규칙을 서버가 다시 풀어야 한다.
 *
 * <p>요청자를 본문으로 받지 않는다. 토큰에서 나온다 — 남의 이름으로 초대하는 것을 막는 유일한 장치다.
 */
public record ChatRoomInviteRequest(
    @Schema(description = "초대할 회원번호. 그 모집글에 댓글을 쓴 사람이어야 한다", example = "12")
        @NotNull(message = "초대할 회원번호는 필수입니다.")
        Long userId) {}
