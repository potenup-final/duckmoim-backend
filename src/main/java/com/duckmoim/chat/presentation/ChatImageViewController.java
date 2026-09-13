package com.duckmoim.chat.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.chat.service.ChatImageViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 채팅 이미지 열람 (CH-15).
 *
 * <p><b>이 컨트롤러가 정본에 열람 경로를 연다.</b> API-설계.md 2-11 에 이미지 엔드포인트가 아직 없다 — CH-14(STAR-115)가 업로드 둘을 먼저
 * 열었고 그때도 위키 반영이 뒤따랐다. 위키는 별도 클론에서 따라온다.
 *
 * <pre>
 * GET /chat-rooms/{roomId}/images?messageIds=51,52,53
 *   → [ { messageId, viewUrl, expiresInSeconds }, … ]
 *     브라우저 ──GET viewUrl──▶ S3                 서버를 지나지 않는다
 * </pre>
 *
 * <p><b>한 번에 여러 장을 받는다</b> (PR #152 리뷰 ③). 한 장에 요청 하나였을 때 사진 20장짜리 방을 여는 것이 20요청 · 60쿼리였다 — 지금은 사진
 * 수와 무관하게 1요청 · 3쿼리다.
 *
 * <p><b>이미지 번호가 아니라 메시지 번호로 받는다.</b> 「그 사진이 실린 메시지가 아직 보이는가」(CH-12 · AD-09)를 물어야 하는데, 이미지 번호로는 그
 * 질문에 답할 수 없다 — 근거는 {@code ChatImageViewService} 에 있다. 클라이언트는 목록에서 {@code messageId} 와 {@code
 * imageId} 를 나란히 받으므로 더 쥐어야 하는 값이 없다.
 *
 * <p><b>업로드와 메서드가 갈린다.</b> 같은 {@code /images} 경로에 {@code POST} 는 업로드 서명 발급이다 ({@code
 * ChatImageController}) — 그쪽은 <b>아직 대화에 실리지 않은</b> 사진이라 올린 사람 본인만 만지고, 이쪽은 <b>대화에 실린</b> 사진이라 방 멤버
 * 전체의 것이다.
 *
 * <p><b>등급은 이미 열려 있다.</b> {@code SIGNUP_READ} 의 {@code /api/v1/chat-rooms/**} 가 덮는다 — 읽기라 제재 인터셉터에도
 * 걸리지 않는다 (도메인 6장: 읽기가 막히는 것은 {@code BANNED} 뿐이다). 등급은 {@code EndpointGradeTest} 의 표가 지킨다.
 *
 * <p><b>{@code authUser} 는 null 이 될 수 없다.</b> 그 등급이 익명 요청을 401, 가입 미완료를 403 으로 관문에서 끝낸다.
 */
@Tag(name = "채팅", description = "채팅 이미지")
@RestController
@RequestMapping("/api/v1/chat-rooms/{roomId}/images")
@RequiredArgsConstructor
public class ChatImageViewController {

  /**
   * 한 요청이 물어볼 수 있는 최대 장수.
   *
   * <p>메시지 목록의 한 페이지 상한과 같은 값이다 (API-설계.md 「검증 상한」) — 화면이 한 페이지를 그리면서 부르는 것이 이 경로라, 그보다 많이 물을 자리가
   * 없다.
   *
   * <p><b>넘으면 400 이다.</b> 목록의 {@code size} 처럼 조용히 자르지 않는다 — 잘린 사진은 화면에서 <b>그냥 안 보이는</b> 것이 되고,
   * 클라이언트는 왜 안 보이는지 알 방법이 없다.
   *
   * <p><b>{@code @Validated} 를 붙이지 않는다.</b> 붙이면 검증이 AOP 프록시로 넘어가 {@code
   * ConstraintViolationException} 이 나는데, {@code GlobalExceptionHandler} 가 잡는 것은 Spring MVC 내장 검증의
   * {@code HandlerMethodValidationException} 이라 <b>500 이 나간다.</b> 실측했다 — 닉네임 중복 확인({@code
   * UserController})이 같은 방식으로 애너테이션만 걸어 400 을 낸다.
   */
  private static final int MAX_MESSAGE_IDS = 50;

  private final ChatImageViewService chatImageViewService;

  /**
   * 사진을 볼 수 있는 주소를 발급한다 (CH-15).
   *
   * <p><b>사진 바이트를 이 응답에 싣지 않는다.</b> 서버가 S3 에서 받아 다시 내려주면 업로드를 직접 올리게 한 판단(CH-14)이 열람에서 되돌려진다 — 대화
   * 사진은 크고, 한 화면에 여러 장이 뜬다.
   *
   * <p><b>볼 수 없는 번호는 응답에서 빠진다.</b> 한 장이 안 보인다고 나머지를 못 받게 하지 않는다 — 목록을 그리는 중에 남이 자기 사진을 지우는 것은 정상적인
   * 일이다. 그래서 응답 길이가 요청 길이보다 짧을 수 있고, 화면은 {@code messageId} 로 맞춘다.
   *
   * <p><b>매번 물어야 한다.</b> 주소에 수명이 있어 화면을 오래 열어 두면 끊긴다 — 그때 다시 부르는 것이 정상 경로이고, 그 사이 방을 나갔으면 403 이 난다.
   * 같은 사진이면 서버가 아직 살아 있는 서명을 그대로 돌려주므로 되묻는 비용이 작다.
   */
  @Operation(
      summary = "채팅 이미지 열람 주소 발급",
      description =
          "방 멤버만 받을 수 있다. 공개 주소를 쓰지 않으므로 이 주소가 사진을 보는 유일한 길이다. "
              + "지운 메시지·블라인드된 메시지·다른 방의 메시지는 응답에서 빠진다.")
  @GetMapping
  public List<ChatImageViewResponse> issue(
      @PathVariable Long roomId,
      @Parameter(description = "사진이 실린 메시지 번호들. 최대 50개", example = "51,52,53")
          @RequestParam
          @NotEmpty
          @Size(max = MAX_MESSAGE_IDS)
          List<Long> messageIds,
      @AuthenticationPrincipal AuthUser authUser) {

    return chatImageViewService.viewUrlsOf(roomId, messageIds, authUser.userId()).stream()
        .map(ChatImageViewResponse::from)
        .toList();
  }
}
