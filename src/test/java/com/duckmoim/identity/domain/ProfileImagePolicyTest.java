package com.duckmoim.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.exception.UserErrorCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 프로필 이미지의 형식·크기 판정 (AU-08).
 *
 * <p><b>같은 판정을 두 시점에 쓴다</b> — 발급 때는 클라이언트가 선언한 값, 확정 때는 S3 에 물어본 실제 값. 그래서 이 값 객체가 도메인에 있고 단위 테스트로
 * 충분하다.
 */
class ProfileImagePolicyTest {

  private static final long MAX = 5 * 1024 * 1024;

  private final ProfileImagePolicy policy =
      new ProfileImagePolicy(List.of("image/jpeg", "image/png", "image/webp"), MAX);

  @ParameterizedTest
  @ValueSource(strings = {"image/jpeg", "image/png", "image/webp"})
  @DisplayName("허용된 형식은 통과한다.")
  void validate_allowedTypes(String contentType) {
    assertThatCode(() -> policy.validate(contentType, 1024)).doesNotThrowAnyException();
  }

  /** GIF · SVG 를 뺀 것은 아바타에 애니메이션과 스크립트가 들어갈 이유가 없어서다. SVG 는 스크립트를 품을 수 있다. */
  @ParameterizedTest
  @ValueSource(strings = {"image/gif", "image/svg+xml", "application/pdf", "text/plain"})
  @DisplayName("허용되지 않은 형식은 거부된다.")
  void validate_disallowedTypes(String contentType) {
    assertThatThrownBy(() -> policy.validate(contentType, 1024))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorCode", UserErrorCode.USER_PROFILE_IMAGE_TYPE_NOT_ALLOWED);
  }

  @Test
  @DisplayName("형식을 주지 않으면 거부된다.")
  void validate_nullType() {
    assertThatThrownBy(() -> policy.validate(null, 1024))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorCode", UserErrorCode.USER_PROFILE_IMAGE_TYPE_NOT_ALLOWED);
  }

  /** 상한은 「이하」다. 경계에서 갈리면 5MB 사진이 되는 날과 안 되는 날이 생긴다. */
  @Test
  @DisplayName("상한과 똑같은 크기는 통과한다.")
  void validate_atLimit() {
    assertThatCode(() -> policy.validate("image/webp", MAX)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("상한을 1바이트 넘으면 거부된다.")
  void validate_overLimit() {
    assertThatThrownBy(() -> policy.validate("image/webp", MAX + 1))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_PROFILE_IMAGE_TOO_LARGE);
  }

  /** 0바이트 파일은 이미지가 아니다. S3 는 빈 객체를 정상으로 받아들이므로 여기서 막는다. */
  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  @DisplayName("크기가 0 이하면 거부된다.")
  void validate_nonPositive(long contentLength) {
    assertThatThrownBy(() -> policy.validate("image/webp", contentLength))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_PROFILE_IMAGE_TOO_LARGE);
  }

  /** 확장자가 없으면 주소만 보고 종류를 짐작하는 자리에서 어긋난다. */
  @Test
  @DisplayName("형식마다 확장자가 붙는다.")
  void extensionOf() {
    assertThat(policy.extensionOf("image/jpeg")).isEqualTo("jpg");
    assertThat(policy.extensionOf("image/png")).isEqualTo("png");
    assertThat(policy.extensionOf("image/webp")).isEqualTo("webp");
  }

  /** 설정의 허용 목록에 확장자 표가 모르는 형식이 추가돼도 키 생성이 터지지 않아야 한다. */
  @Test
  @DisplayName("모르는 형식에도 확장자를 준다.")
  void extensionOf_unknown() {
    assertThat(policy.extensionOf("image/avif")).isEqualTo("bin");
  }
}
