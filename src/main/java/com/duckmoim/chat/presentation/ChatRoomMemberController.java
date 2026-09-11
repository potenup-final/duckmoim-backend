package com.duckmoim.chat.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.chat.service.ChatRoomInvitation;
import com.duckmoim.chat.service.ChatRoomInviteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 채팅방 멤버 (CH-02).
 *
 * <p><b>모집글 아래 경로다.</b> 방 번호가 아니라 모집글 번호로 받는다 — 진입점이 모집글 상세의 댓글이고 (CM-01) 그 화면에는 방 번호가 없다. 방 하나에
 * 모집글 하나라 (CH-01 · I-16) 어느 쪽으로 부르든 가리키는 방이 같고, 방 번호로 받으면 화면이 초대 한 번에 요청을 두 번 보내게 된다.
 *
 * <p><b>{@code SecurityConfig} 를 건드리지 않았다.</b> {@code SIGNUP_WRITE} 의 {@code /api/v1/posts/**} 가 이
 * 경로를 이미 덮는다. 등급은 {@code EndpointGradeTest} 의 표가 지킨다 — 거기 한 줄을 더했다.
 *
 * <p><b>{@code authUser} 는 null 이 될 수 없다.</b> 그 등급이 익명 요청을 401, 가입 미완료를 403 으로 관문에서 끝낸다.
 */
@Tag(name = "채팅", description = "채팅방 멤버")
@RestController
@RequestMapping("/api/v1/posts/{postId}/chat-room/members")
@RequiredArgsConstructor
public class ChatRoomMemberController {

  private final ChatRoomInviteService chatRoomInviteService;

  /**
   * 댓글 작성자를 방에 초대한다 (CH-02 · CH-02a · CH-03).
   *
   * <p>생성 성공도 200 이다 (API-설계.md 「성공 응답의 상태 코드」). 201 을 쓰지 않는다.
   *
   * <p>방장 판정을 여기서 하지 않는다. 관문은 SIGNUP 까지만 보고 방장 여부는 service 가 본다 ({@code EndpointGradeTest} 의 「HOST
   * 는 관문이 판정하지 않는다」).
   */
  @Operation(
      summary = "채팅방 초대",
      description = "방장만 부를 수 있고 대상은 그 모집글에 댓글을 쓴 사람이다. 수락 단계가 없어 즉시 멤버가 되고 알림은 가지 않는다.")
  @PostMapping
  public ChatRoomInviteResponse invite(
      @PathVariable Long postId,
      @AuthenticationPrincipal AuthUser authUser,
      @Valid @RequestBody ChatRoomInviteRequest request) {

    ChatRoomInvitation invitation =
        chatRoomInviteService.invite(postId, request.userId(), authUser.userId());

    return ChatRoomInviteResponse.from(invitation);
  }
}
