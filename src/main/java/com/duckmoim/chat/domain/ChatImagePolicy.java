package com.duckmoim.chat.domain;

import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.common.exception.BusinessException;
import java.util.List;
import java.util.Map;

/**
 * 채팅 이미지의 형식과 크기 판정 (CH-14).
 *
 * <p><b>같은 규칙을 두 시점에 쓴다.</b> 발급 때는 클라이언트가 <b>선언한</b> 값을 보고, 확정 때는 S3 에 물어본 <b>실제</b> 값을 본다. 둘이 갈라지면
 * 선언만 통과시키고 실제는 안 보는 구멍이 생긴다 — {@code ProfileImagePolicy}(AU-08)가 같은 이유로 같은 자리에 있다.
 *
 * <p><b>그 클래스를 쓰지 않고 새로 둔다.</b> 저장소 포트를 합치지 않기로 한 것과 같은 근거다 (계획서 8.1) — 그쪽은 {@code identity.domain}
 * 이고, 채팅이 그것을 참조하면 컨텍스트 간 객체 참조가 된다 (도메인 3.2 는 ID 참조만 허용한다). <b>허용 목록과 상한도 따로 움직인다</b> — 아바타는 작을
 * 이유가 있고 대화 사진은 그렇지 않다.
 *
 * <p><b>서버가 파일을 못 본다는 것이 이 값 객체의 전제다.</b> 바이트가 브라우저와 S3 사이에서만 오가므로 판정 재료가 메타데이터뿐이다.
 *
 * <p>허용 목록과 상한은 설정에서 온다 — 명세가 「형식·크기 정책은 설정값」으로 못박았다.
 */
public class ChatImagePolicy {

  /**
   * 형식별 확장자.
   *
   * <p>키에 확장자를 붙인다. 없으면 S3 객체가 확장자 없는 이름이 되고, 주소만 보고 종류를 짐작하는 자리에서 어긋난다.
   */
  private static final Map<String, String> EXTENSIONS =
      Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

  private final List<String> allowedContentTypes;
  private final long maxBytes;

  public ChatImagePolicy(List<String> allowedContentTypes, long maxBytes) {
    this.allowedContentTypes = List.copyOf(allowedContentTypes);
    this.maxBytes = maxBytes;
  }

  /**
   * 형식과 크기가 정책 안인지 본다. 벗어나면 <b>어느 쪽이 문제인지 갈라서</b> 던진다.
   *
   * <p>하나로 뭉치면 사용자가 무엇을 고쳐야 하는지 모른다 — 사진을 줄여야 하는지 다른 파일을 골라야 하는지가 다르다.
   *
   * @throws BusinessException 허용 밖 형식이면 {@code CHAT_IMAGE_TYPE_NOT_ALLOWED}, 상한을 넘으면 {@code
   *     CHAT_IMAGE_TOO_LARGE}
   */
  public void validate(String contentType, long contentLength) {
    if (contentType == null || !allowedContentTypes.contains(contentType)) {
      throw new BusinessException(ChatErrorCode.CHAT_IMAGE_TYPE_NOT_ALLOWED);
    }
    if (contentLength <= 0 || contentLength > maxBytes) {
      throw new BusinessException(ChatErrorCode.CHAT_IMAGE_TOO_LARGE);
    }
  }

  /**
   * 그 형식의 확장자.
   *
   * <p>{@link #validate} 를 통과한 형식만 들어온다. 그래도 기본값을 두는 것은 설정의 허용 목록에 여기 없는 형식이 추가됐을 때 <b>키 생성이 터지지
   * 않게</b> 하려는 것이다.
   */
  public String extensionOf(String contentType) {
    return EXTENSIONS.getOrDefault(contentType, "bin");
  }
}
