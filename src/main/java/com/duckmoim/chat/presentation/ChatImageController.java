package com.duckmoim.chat.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.chat.service.ChatImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 채팅 이미지 (CH-14).
 *
 * <p><b>서버가 이미지 바이트를 받지 않는다.</b> 엔드포인트 둘이 <b>서명</b>과 <b>확인</b>이고, 바이트는 브라우저와 저장소 사이에서만 오간다 —
 * API-설계.md 가 프로필 이미지에 대해 적은 근거가 그대로 적용된다: <i>"받으면 업로드 트래픽이 전부 애플리케이션을 지나고, 5MB 파일이 스레드를 붙잡는다"</i>.
 *
 * <pre>
 * POST /chat-rooms/{roomId}/images              서명 발급 → { imageId, uploadUrl }
 *      브라우저 ──PUT uploadUrl──▶ S3           서버를 지나지 않는다
 * PUT  /chat-rooms/{roomId}/images/{imageId}    확정 — 올라온 것을 확인한다
 *      POST /chat-rooms/{roomId}/messages       imageId 를 실어 보낸다
 * </pre>
 *
 * <p><b>메서드가 프로필 이미지와 같은 짝이다</b> ({@code POST} 발급 · {@code PUT} 확정). 경로만 방 아래로 내려왔다 — 이미지가 <b>방에 속한
 * 자원</b>이라 멤버 판정이 필요하고, 그 판정의 입력이 경로의 방 번호다.
 *
 * <p><b>등급이 {@code SIGNUP_WRITE} 의 {@code /api/v1/chat-rooms/**} 로 덮인다.</b> 둘 다 쓰기 메서드라 새로 더할 줄이 없다
 * — 방 아래 <b>읽기</b>만 {@code SIGNUP_READ} 에 줄을 더해야 했다 (CH-09 · STAR-112).
 *
 * <p><b>{@code authUser} 는 null 이 될 수 없다.</b> 그 등급이 익명 요청을 401, 가입 미완료를 403 으로 관문에서 끝낸다.
 */
@Tag(name = "채팅", description = "채팅 이미지")
@RestController
@RequestMapping("/api/v1/chat-rooms/{roomId}/images")
@RequiredArgsConstructor
public class ChatImageController {

  private final ChatImageService chatImageService;

  /**
   * 서명된 업로드 주소를 발급한다 (CH-14).
   *
   * <p><b>여기가 「허용 밖 형식·크기 400」이 나는 자리다.</b> 클라이언트가 선언한 값으로 판정하고, 벗어나면 서명을 만들지 않는다.
   *
   * <p>생성 성공도 200 이다 (API-설계.md 「성공 응답의 상태 코드」). 201 을 쓰지 않는다.
   */
  @Operation(
      summary = "채팅 이미지 업로드 서명 발급",
      description =
          "방 멤버만 발급받을 수 있다. 허용 밖 형식·크기는 400 이다. "
              + "돌려받은 uploadUrl 로 브라우저가 직접 PUT 하고, 그 뒤 확정을 부른다.")
  @PostMapping
  public ChatImageUploadResponse issue(
      @PathVariable Long roomId,
      @AuthenticationPrincipal AuthUser authUser,
      @Valid @RequestBody ChatImageUploadRequest request) {

    return ChatImageUploadResponse.from(
        chatImageService.issueUpload(
            roomId, authUser.userId(), request.contentType(), request.contentLength()));
  }

  /**
   * 올라간 것을 확인한다 (CH-14).
   *
   * <p><b>본문 없는 200 이다.</b> 돌려줄 것이 없다 — 클라이언트는 이미 {@code imageId} 를 쥐고 있고, 다음에 할 일은 그것을 전송에 싣는 것이다.
   *
   * <p><b>이 단계를 지나지 않은 이미지는 전송에서 400 이다</b> — 검증 기준 「업로드 확인 전 메시지 전송 시 400」이 그 줄이다.
   *
   * <p>같은 요청을 두 번 보내도 같은 답이다. 확정 응답을 못 받은 클라이언트가 다시 부르는 것이 정상 경로다.
   */
  @Operation(
      summary = "채팅 이미지 업로드 확정",
      description = "저장소에 올라온 것을 확인하고 실제 형식·크기를 다시 판정한다. 올라온 것이 없으면 400 이다.")
  @PutMapping("/{imageId}")
  public void confirm(
      @PathVariable Long roomId,
      @PathVariable Long imageId,
      @AuthenticationPrincipal AuthUser authUser) {

    chatImageService.confirm(roomId, authUser.userId(), imageId);
  }
}
