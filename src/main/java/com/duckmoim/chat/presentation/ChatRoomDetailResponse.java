package com.duckmoim.chat.presentation;

import com.duckmoim.chat.service.ChatRoomDetailView;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 방 상세 (CH-06).
 *
 * <p>「모집글 요약 · 멤버 목록 · 채팅 가능 여부」 셋을 그대로 담는다. 빈 방(멤버가 방장뿐인 방)도 이 모양 그대로다 — 별도 안내 화면을 두지 않는다는 요구사항이 곧
 * 응답 모양이 하나뿐이라는 뜻이다.
 *
 * @param writable 지금 메시지를 쓸 수 있는가. 만남시각 + 7일이 지나면 {@code false} 다 (CH-08)
 */
public record ChatRoomDetailResponse(
    Long roomId,
    ChatRoomPostSummaryResponse post,
    List<ChatRoomMemberResponse> members,
    boolean writable) {

  static ChatRoomDetailResponse from(ChatRoomDetailView view) {
    return new ChatRoomDetailResponse(
        view.roomId(),
        new ChatRoomPostSummaryResponse(view.postId(), view.postTitle(), toKst(view.meetAt())),
        view.members().stream().map(ChatRoomMemberResponse::from).toList(),
        view.writable());
  }

  private static OffsetDateTime toKst(LocalDateTime storedInUtc) {
    return storedInUtc
        .atOffset(ZoneOffset.UTC)
        .atZoneSameInstant(ZoneId.of("Asia/Seoul"))
        .toOffsetDateTime();
  }

  /** 방이 딸린 모집글의 요약. 상세 전체({@code /posts/{postId}})는 별도 조회다 (PO-11). */
  public record ChatRoomPostSummaryResponse(Long postId, String title, OffsetDateTime meetAt) {}
}
