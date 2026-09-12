package com.duckmoim.chat.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.chat.service.AdminChatReadService;
import com.duckmoim.chat.service.MessageSlice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 신고가 접수된 방의 대화를 관리자가 읽는다 (AD-08).
 *
 * <p><b>{@code /api/v1/admin} 아래에 있다.</b> 인가는 인터셉터 한 곳이 그 접두어 전체에 건다 — 컨트롤러마다 어노테이션을 흩뿌리지 않는다는 것이
 * 결정 D-5 의 세 조건 중 둘째다 (API-설계.md 「2-7. 백오피스 (Admin)」).
 *
 * <p><b>멤버용 경로와 클래스를 나눈 이유는 인가 축이 다르기 때문이다.</b> {@code ChatMessageController} 는 방 멤버인지를 보고 여기는
 * 관리자인지를 본다. 한 클래스에 담으면 그 둘이 한 파일에서 섞이고, 섞인 자리에서 빠뜨린 판정은 <b>남의 사적인 대화</b>를 연다.
 */
@Tag(name = "백오피스 · 채팅", description = "신고 접수된 방의 대화 열람")
@RestController
@RequestMapping("/api/v1/admin/chat-rooms/{roomId}/messages")
@RequiredArgsConstructor
public class AdminChatController {

  private final AdminChatReadService adminChatReadService;

  /**
   * 대화를 읽는다 (AD-08).
   *
   * <p><b>{@code reportId} 가 필수다.</b> 같은 이름을 선택으로 받는 비밀 댓글 열람(CM-17)과 성격이 다르다 — 그쪽은 감사 로그를 풍부하게 하는
   * 값이고 <b>여기는 열람 자격 그 자체</b>다. 넘긴 신고가 이 방이나 이 방의 메시지를 가리키지 않으면 403 이다.
   *
   * <p><b>호출마다 감사 로그가 남는다</b> (AD-05). 열람 한 번에 한 건이다.
   */
  @Operation(
      summary = "채팅 대화 열람",
      description = "신고가 접수된 방만 볼 수 있다. 호출마다 감사 로그가 남는다. 지운 메시지의 본문도 함께 온다.")
  @GetMapping
  public MessageListResponse read(
      @PathVariable Long roomId,
      @RequestParam @NotNull Long reportId,
      @AuthenticationPrincipal AuthUser authUser,
      @ModelAttribute MessageListRequest request) {

    MessageSlice slice =
        adminChatReadService.readMessages(request.toQuery(roomId), authUser.userId(), reportId);

    return MessageListResponse.from(slice);
  }
}
