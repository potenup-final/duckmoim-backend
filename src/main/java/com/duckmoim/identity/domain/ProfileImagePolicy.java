package com.duckmoim.identity.domain;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.exception.UserErrorCode;
import java.util.List;
import java.util.Map;

/**
 * 프로필 이미지의 형식과 크기 판정 (AU-08).
 *
 * <p><b>이 판정이 도메인에 있는 이유</b> — 같은 규칙을 <b>두 시점</b>에 써야 한다. 발급 때는 클라이언트가 <b>선언한</b> 값을 보고, 확정 때는 S3 에
 * 물어본 <b>실제</b> 값을 본다. 둘이 갈라지면 선언만 통과시키고 실제는 안 보는 구멍이 생긴다.
 *
 * <p><b>서버가 파일을 못 본다는 것이 이 값 객체의 전제다.</b> Presigned URL 은 브라우저가 S3 로 직접 올려서 바이트가 서버를 지나지 않는다. 그래서
 * 검증 기준의 「용량·MIME 위반 400」을 낼 자리가 <b>메타데이터뿐</b>이다.
 *
 * <p>허용 목록과 상한은 설정에서 온다 — 위키에 값이 없어 정했고, 코드에 박으면 바꿀 때 배포가 필요해진다.
 */
public class ProfileImagePolicy {

  /**
   * 형식별 확장자.
   *
   * <p>키를 만들 때 확장자를 붙인다. 없으면 S3 객체가 확장자 없는 이름이 되고, 브라우저가 주소만 보고 종류를 짐작하는 자리(다운로드·미리보기)에서 어긋난다.
   */
  private static final Map<String, String> EXTENSIONS =
      Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

  private final List<String> allowedContentTypes;
  private final long maxBytes;

  public ProfileImagePolicy(List<String> allowedContentTypes, long maxBytes) {
    this.allowedContentTypes = List.copyOf(allowedContentTypes);
    this.maxBytes = maxBytes;
  }

  /**
   * 형식과 크기가 정책 안인지 본다. 벗어나면 <b>어느 쪽이 문제인지 갈라서</b> 던진다.
   *
   * <p>「업로드에 실패했습니다」 하나로 뭉치면 사용자가 무엇을 고쳐야 하는지 모른다 — 사진을 줄여야 하는지 다른 파일을 골라야 하는지가 다르다.
   *
   * @throws BusinessException 허용되지 않는 형식이면 {@code USER_PROFILE_IMAGE_TYPE_NOT_ALLOWED}, 상한을 넘으면
   *     {@code USER_PROFILE_IMAGE_TOO_LARGE}
   */
  public void validate(String contentType, long contentLength) {
    if (contentType == null || !allowedContentTypes.contains(contentType)) {
      throw new BusinessException(UserErrorCode.USER_PROFILE_IMAGE_TYPE_NOT_ALLOWED);
    }
    if (contentLength <= 0 || contentLength > maxBytes) {
      throw new BusinessException(UserErrorCode.USER_PROFILE_IMAGE_TOO_LARGE);
    }
  }

  /**
   * 그 형식의 확장자.
   *
   * <p>{@link #validate} 를 통과한 형식만 들어온다 — 목록에 없으면 그 앞에서 이미 400 이다. 그래도 기본값을 두는 것은 설정의 허용 목록에 여기 없는
   * 형식이 추가됐을 때 <b>키 생성이 터지지 않게</b> 하려는 것이다.
   */
  public String extensionOf(String contentType) {
    return EXTENSIONS.getOrDefault(contentType, "bin");
  }
}
