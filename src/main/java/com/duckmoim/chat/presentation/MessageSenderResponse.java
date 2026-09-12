package com.duckmoim.chat.presentation;

import com.duckmoim.chat.service.MessageView;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 말풍선에 붙는 보낸 사람 (CH-09).
 *
 * <p><b>탈퇴한 회원은 자리표시자로 나간다</b> — {@code nickname} 이 {@code "탈퇴한 회원"}, {@code profileImageUrl} 은
 * {@code null} 이다 (AU-11 · {@code AuthorDisplay}). 익명화 판정을 여기서 다시 하지 않는다.
 *
 * <p><b>나간 사람(CH-04)의 옛 메시지도 이름을 갖는다.</b> 방 상세의 멤버 목록에는 없지만 메시지마다 조인해 읽기 때문이다 ({@code
 * AuthoredMessage}).
 */
public record MessageSenderResponse(
    @Schema(description = "회원번호", example = "11") Long userId,
    @Schema(description = "닉네임. 탈퇴했으면 \"탈퇴한 회원\"", example = "덕후1") String nickname,
    @Schema(description = "프로필 이미지. 없거나 탈퇴했으면 null", nullable = true) String profileImageUrl) {

  static MessageSenderResponse from(MessageView view) {
    return new MessageSenderResponse(
        view.senderId(), view.sender().nickname(), view.sender().profileImageUrl());
  }
}
