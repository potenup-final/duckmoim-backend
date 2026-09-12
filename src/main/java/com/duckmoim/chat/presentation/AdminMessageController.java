package com.duckmoim.chat.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.chat.service.AdminMessageBlindService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 신고 처리 결과로 메시지를 가린다 (AD-09).
 *
 * <p><b>경로가 방 아래가 아니다.</b> 메시지 번호가 전역으로 유일해서 방을 거칠 이유가 없고, 댓글 블라인드가 {@code
 * /admin/comments/{commentId}/blind} 인 것과 같은 모양이다 (AD-07).
 */
@Tag(name = "백오피스 · 채팅", description = "신고 처리 결과 조치")
@RestController
@RequestMapping("/api/v1/admin/messages")
@RequiredArgsConstructor
public class AdminMessageController {

  private final AdminMessageBlindService adminMessageBlindService;

  /**
   * 메시지를 가린다 (AD-09).
   *
   * <p><b>본문 없는 200 이다</b> (API-설계.md 「성공 응답의 상태 코드」). 204 를 쓰지 않는다. {@code ACTIVE → BLINDED} 전이
   * 하나뿐이라 무엇을 바꿀지 실어 보낼 것이 없다.
   */
  @Operation(summary = "메시지 블라인드", description = "이미 지워지거나 가려진 메시지는 409 다.")
  @PostMapping("/{messageId}/blind")
  public void blind(@PathVariable Long messageId, @AuthenticationPrincipal AuthUser authUser) {
    adminMessageBlindService.blind(messageId, authUser.userId());
  }
}
