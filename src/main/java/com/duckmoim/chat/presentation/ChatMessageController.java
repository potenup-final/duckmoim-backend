package com.duckmoim.chat.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.chat.service.ChatMessageDeleteService;
import com.duckmoim.chat.service.ChatMessageQueryService;
import com.duckmoim.chat.service.ChatMessageSendService;
import com.duckmoim.chat.service.ChatStreamService;
import com.duckmoim.chat.service.MessageSlice;
import com.duckmoim.chat.service.SentMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 채팅 메시지 (CH-07 · CH-08 · CH-09 · CH-10 · CH-12).
 *
 * <p><b>이 컨트롤러가 정본에 전송 경로를 연다.</b> API-설계.md 2-11 이 <i>"전송을 막는 409 자체는 이 절에 없다 — {@code Message}
 * (CH-07)가 아직 없어 전송 엔드포인트가 없고, 그 티켓이 문을 연다"</i> 고 적어 두었다. 위키 반영은 별도 클론에서 따라온다.
 *
 * <p><b>방 번호로 받는다.</b> 2-11 이 「목록·상세는 방 번호로 받는다」로 정했고 진입점이 방 화면이다 — 거기까지 온 클라이언트는 방 번호를 이미 쥐고 있다.
 * 초대(CH-02)만 모집글 번호인 것은 그쪽 진입점이 모집글 상세의 댓글이라 그 화면에 방 번호가 없기 때문이다.
 *
 * <p><b>등급이 메서드로 갈린다.</b> {@code POST}·{@code DELETE} 는 {@code SIGNUP_WRITE} 의 {@code
 * /api/v1/chat-rooms/**} 가 덮고, {@code GET} 은 CH-05 · CH-06 이 더한 {@code SIGNUP_READ} 가 덮는다. 그런데 그쪽
 * 목록이 {@code /api/v1/chat-rooms/*} 라 <b>한 칸 깊은 이 목록 경로에 닿지 않아</b> 줄을 하나 더했다. 셋 다 같은 {@code SIGNUP}
 * 이고, 등급은 {@code EndpointGradeTest} 의 표가 지킨다.
 *
 * <p><b>{@code authUser} 는 null 이 될 수 없다.</b> 그 등급이 익명 요청을 401, 가입 미완료를 403 으로 관문에서 끝낸다.
 */
@Tag(name = "채팅", description = "채팅 메시지")
@RestController
@RequestMapping("/api/v1/chat-rooms/{roomId}/messages")
@RequiredArgsConstructor
public class ChatMessageController {

  /**
   * 연결을 열어 두는 시간.
   *
   * <p>무한이 아닌 이유는 죽은 연결을 언젠가는 걷어내야 해서다. 끊긴 뒤를 잇는 것은 {@code CH-11}(STAR-114)이고, 브라우저의 {@code
   * EventSource} 는 끊기면 스스로 다시 붙는다.
   */
  private static final long STREAM_TIMEOUT_MILLIS = 30 * 60 * 1000L;

  private final ChatMessageSendService chatMessageSendService;
  private final ChatMessageQueryService chatMessageQueryService;
  private final ChatMessageDeleteService chatMessageDeleteService;
  private final ChatStreamService chatStreamService;

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
          "방 멤버만 보낼 수 있다. 만남시각 + 7일이 지나면 409 다. 같은 clientMessageId 로 다시 보내면 새로 저장하지 않고 먼저 보낸 것을 그대로 돌려준다. "
              + "imageId 를 실으면 사진 메시지가 되고, 업로드 확정을 마치지 않은 번호는 400 이다.")
  @PostMapping
  public ChatMessageResponse send(
      @PathVariable Long roomId,
      @AuthenticationPrincipal AuthUser authUser,
      @Valid @RequestBody ChatMessageSendRequest request) {

    SentMessage sent =
        chatMessageSendService.send(
            roomId,
            authUser.userId(),
            request.clientMessageId(),
            request.contentOrEmpty(),
            request.imageId());

    return ChatMessageResponse.from(sent);
  }

  /**
   * 메시지를 최신부터 한 페이지 읽는다 (CH-09).
   *
   * <p><b>OFFSET 을 쓰지 않는다</b> — 검증 기준이 「페이지 경계에서 누락·중복 없음」이고, 뒤에서 새 메시지가 계속 들어오는 목록이라 OFFSET 은 반드시
   * 어긋난다. 커서여야 하는 이유가 성능이 아니다.
   *
   * <p>방 멤버 판정은 여기서 하지 않는다. 관문은 {@code SIGNUP} 까지만 보고 service 가 본다.
   */
  @Operation(
      summary = "메시지 목록 조회",
      description = "방 멤버만 볼 수 있다. 최신순 커서이고 지운 메시지는 본문 키 없이 자리표시자로 남는다.")
  @GetMapping
  public MessageListResponse list(
      @PathVariable Long roomId,
      @AuthenticationPrincipal AuthUser authUser,
      @ModelAttribute MessageListRequest request) {

    MessageSlice slice =
        chatMessageQueryService.findMessages(request.toQuery(roomId), authUser.userId());

    return MessageListResponse.from(slice);
  }

  /**
   * 내가 보낸 메시지를 지운다 (CH-12).
   *
   * <p><b>본문 없는 200 이다</b> (API-설계.md 「성공 응답의 상태 코드」). 204 를 쓰지 않는다.
   *
   * <p><b>방장은 못 지운다.</b> 댓글(CM-10)이 작성자와 방장 둘에게 준 것과 갈리는 자리이고, 명세의 상세가 「작성자 본인만」이다.
   */
  @Operation(summary = "메시지 삭제", description = "보낸 사람만 지울 수 있다. 소프트 삭제라 목록에는 본문 없는 자리표시자로 남는다.")
  @DeleteMapping("/{messageId}")
  public void delete(
      @PathVariable Long roomId,
      @PathVariable Long messageId,
      @AuthenticationPrincipal AuthUser authUser) {

    chatMessageDeleteService.delete(roomId, messageId, authUser.userId());
  }

  /**
   * 그 방의 메시지를 실시간으로 받는다 (CH-10).
   *
   * <p><b>응답이 끝나지 않는 요청이다.</b> {@code text/event-stream} 으로 열어 두고 사건이 생길 때마다 한 덩어리씩 흘려보낸다 — {@code
   * SseEmitter} 를 반환하면 스프링이 그 요청을 비동기로 돌려둔다.
   *
   * <p><b>타임아웃을 30분으로 둔다.</b> 무한으로 두면 죽은 연결이 영원히 남고, 너무 짧으면 재연결이 잦아진다. 끊긴 뒤 빠진 것을 메우는 일은 {@code
   * CH-11}(STAR-114) 몫이라, 여기서는 <b>끊기는 것 자체를 정상으로 다룬다</b> — 브라우저의 {@code EventSource} 가 알아서 다시 붙는다.
   *
   * <p><b>ALB 의 유휴 타임아웃(기본 60초)보다 짧게 무언가를 보내야 한다.</b> 대화가 없는 방은 한 시간도 조용한데, 그러면 ALB 가 먼저 끊는다. 연결 직후
   * 주석 한 줄을 보내 선로를 여는 것까지가 이 메서드이고, 이어지는 하트비트는 {@code ChatStreamHeartbeat} 가 진다.
   *
   * <p>멤버 판정을 여기서 하지 않는다. 관문은 {@code SIGNUP} 까지만 보고 방 멤버 여부는 service 가 본다.
   */
  @Operation(
      summary = "메시지 실시간 수신",
      description =
          "방 멤버만 열 수 있다. text/event-stream 으로 메시지가 생길 때마다 밀어 준다. "
              + "id 줄이 messageId 라 브라우저가 재연결할 때 Last-Event-ID 로 돌려준다.")
  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@PathVariable Long roomId, @AuthenticationPrincipal AuthUser authUser) {

    SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
    Runnable release =
        chatStreamService.open(roomId, authUser.userId(), new SseChatStreamSession(emitter));

    // 셋 다 걸어야 한다. 정상 종료(complete)·타임아웃·오류는 서로 다른 콜백이고,
    // 하나라도 빠지면 그 경로로 끝난 연결이 목록에 남아 방마다 쌓인다.
    emitter.onCompletion(release);
    emitter.onTimeout(release);
    emitter.onError(error -> release.run());

    return emitter;
  }
}
