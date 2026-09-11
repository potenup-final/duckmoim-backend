package com.duckmoim.chat.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.chat.service.ChatMessageSendService;
import com.duckmoim.chat.service.SentMessage;
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
 * 채팅 메시지 (CH-07 · CH-08).
 *
 * <p><b>이 컨트롤러가 정본에 전송 경로를 연다.</b> API-설계.md 2-11 이 <i>"전송을 막는 409 자체는 이 절에 없다 — {@code Message}
 * (CH-07)가 아직 없어 전송 엔드포인트가 없고, 그 티켓이 문을 연다"</i> 고 적어 두었다. 위키 반영은 별도 클론에서 따라온다.
 *
 * <p><b>방 번호로 받는다.</b> 2-11 이 「목록·상세는 방 번호로 받는다」로 정했고 진입점이 방 화면이다 — 거기까지 온 클라이언트는 방 번호를 이미 쥐고 있다.
 * 초대(CH-02)만 모집글 번호인 것은 그쪽 진입점이 모집글 상세의 댓글이라 그 화면에 방 번호가 없기 때문이다.
 *
 * <p><b>{@code SecurityConfig} 의 {@code SIGNUP_WRITE} 에 {@code /api/v1/chat-rooms/**} 를 더했다.</b>
 * CH-05 · CH-06 이 더한 {@code SIGNUP_READ} 는 GET 전용이라 이 POST 를 덮지 않는다. 등급은 {@code EndpointGradeTest}
 * 의 표가 지킨다.
 *
 * <p><b>{@code authUser} 는 null 이 될 수 없다.</b> 그 등급이 익명 요청을 401, 가입 미완료를 403 으로 관문에서 끝낸다.
 */
@Tag(name = "채팅", description = "채팅 메시지")
@RestController
@RequestMapping("/api/v1/chat-rooms/{roomId}/messages")
@RequiredArgsConstructor
public class ChatMessageController {

  private final ChatMessageSendService chatMessageSendService;

  /**
   * 메시지를 보낸다 (CH-07 · CH-08).
   *
   * <p>생성 성공도 200 이다 (API-설계.md 「성공 응답의 상태 코드」). 201 을 쓰지 않는다.
   *
   * <p>멤버 판정을 여기서 하지 않는다. 관문은 {@code SIGNUP} 까지만 보고 방 멤버 여부는 service 가 본다 ({@code
   * EndpointGradeTest} 의 「HOST 는 관문이 판정하지 않는다」와 같은 선).
   */
  @Operation(
      summary = "메시지 전송",
      description =
          "방 멤버만 보낼 수 있다. 만남시각 + 7일이 지나면 409 다. 같은 clientMessageId 로 다시 보내면 새로 저장하지 않고 먼저 보낸 것을 그대로 돌려준다.")
  @PostMapping
  public ChatMessageResponse send(
      @PathVariable Long roomId,
      @AuthenticationPrincipal AuthUser authUser,
      @Valid @RequestBody ChatMessageSendRequest request) {

    SentMessage sent =
        chatMessageSendService.send(
            roomId, authUser.userId(), request.clientMessageId(), request.content());

    return ChatMessageResponse.from(sent);
  }
}
