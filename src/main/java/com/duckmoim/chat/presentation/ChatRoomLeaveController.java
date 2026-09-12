package com.duckmoim.chat.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.chat.service.ChatRoomLeaveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 채팅방 퇴장 (CH-04).
 *
 * <p><b>방 아래 경로이고 방 번호로 받는다.</b> 진입점이 방 화면이라 클라이언트가 방 번호를 이미 쥐고 있다 (API-설계.md 「2-11. 채팅 (Chat) ·
 * 2차」의 「목록·상세는 방 번호로 받는다」와 같은 근거). 초대만 모집글 번호인 것은 그쪽 진입점이 모집글 상세의 댓글이기 때문이고, 그래서 {@code
 * ChatRoomMemberController} 와 경로 계열이 갈린다 — 같은 「멤버」를 다루지만 부르는 화면이 다르다.
 *
 * <p><b>대상이 {@code /me} 다.</b> 명세가 <i>"멤버가 스스로 나간다"</i> 이고 남을 내보내는 기능이 2차에 없다. 회원번호를 경로에 두면 없는 권한을
 * 있는 것처럼 그려 놓고 service 에서 막게 되는데, 그 자리는 언젠가 조용히 열린다.
 *
 * <p><b>본문 없이 200 이다</b> (API-컨벤션.md 「Status Code 규칙」). 204 가 표에 없고, 나간 뒤 돌려줄 상태가 「그 방이 더는 보이지 않는다」
 * 하나라 담을 것이 없다 — 화면은 방 목록으로 돌아간다.
 *
 * <p><b>{@code SecurityConfig} 를 건드리지 않았다.</b> {@code SIGNUP_WRITE} 의 {@code /api/v1/chat-rooms/**}
 * 가 이 경로를 이미 덮는다. 반대로 {@code SanctionGateConfig} 는 <b>덮으면 안 되는</b> 자리라 거기서만 예외로 뺀다.
 *
 * <p><b>{@code authUser} 는 null 이 될 수 없다.</b> 그 등급이 익명 요청을 401, 가입 미완료를 403 으로 관문에서 끝낸다.
 */
@Tag(name = "채팅", description = "채팅방 퇴장")
@RestController
@RequestMapping("/api/v1/chat-rooms/{roomId}/members/me")
@RequiredArgsConstructor
public class ChatRoomLeaveController {

  private final ChatRoomLeaveService chatRoomLeaveService;

  /**
   * 내가 그 방을 나간다 (CH-04).
   *
   * <p>방장인지는 여기서 보지 않는다. 관문은 {@code SIGNUP} 까지만 보고 방장 여부는 service 가 모집글을 읽어 판정한다 ({@code
   * EndpointGradeTest} 의 「HOST 는 관문이 판정하지 않는다」).
   */
  @Operation(
      summary = "채팅방 나가기",
      description = "멤버가 스스로 나간다. 방장은 나갈 수 없고 409 다. 나가면 목록에서 사라지고 다시 초대받을 수 없다.")
  @DeleteMapping
  public void leave(@PathVariable Long roomId, @AuthenticationPrincipal AuthUser authUser) {
    chatRoomLeaveService.leave(roomId, authUser.userId());
  }
}
