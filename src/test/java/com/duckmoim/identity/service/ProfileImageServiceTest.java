package com.duckmoim.identity.service;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.ProfileImageStorage;
import com.duckmoim.identity.domain.SignupStatus;
import com.duckmoim.identity.domain.UploadedImage;
import com.duckmoim.identity.exception.UserErrorCode;
import com.duckmoim.identity.infra.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발급과 확정 (AU-08).
 *
 * <p><b>저장소를 목으로 세운다.</b> 진짜 S3 를 부르면 자격증명과 버킷이 필요하고, 무엇보다 <b>테스트가 검증하려는 것이 저장소가 아니다</b> — 키를 어떻게
 * 만들고, 확정에서 무엇을 보고, 무엇을 박는지다. 대역({@code StubProfileImageStorage})을 쓰지 않은 것은 그것이 늘 「없음」을 답해 성공 경로를
 * 만들 수 없기 때문이다.
 *
 * <p>회원 행은 진짜 DB 로 읽는다 — 확정이 실제로 {@code profile_image_url} 을 박는지 봐야 한다.
 */
@SpringBootTest
@Transactional
@DisplayName("프로필 이미지 업로드")
class ProfileImageServiceTest {

  private static final String WEBP = "image/webp";
  private static final long ONE_MB = 1024 * 1024;

  @Autowired private ProfileImageService profileImageService;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private ProfileImageStorage storage;

  @BeforeEach
  void stubStorage() {
    given(storage.presignUpload(anyString(), anyString()))
        .willAnswer(invocation -> "https://signed.example/" + invocation.getArgument(0));
    given(storage.publicUrlOf(anyString()))
        .willAnswer(invocation -> "https://cdn.example/" + invocation.getArgument(0));
  }

  @Test
  @DisplayName("발급하면 서명된 주소와 객체 키가 온다.")
  void issueUpload() {
    long userId = aUser().insert(jdbcTemplate);

    ProfileImageUpload upload =
        profileImageService.issueUpload(new ProfileImageUploadCommand(userId, WEBP, ONE_MB));

    assertThat(upload.uploadUrl()).isNotBlank();
    assertThat(upload.objectKey()).startsWith("profile/" + userId + "/").endsWith(".webp");
    assertThat(upload.expiresInSeconds()).isPositive();
  }

  /** 키에 회원번호가 없으면 확정 때 소유자를 검사할 방법이 없다 — 그래서 접두어가 계약이다. */
  @Test
  @DisplayName("객체 키는 회원번호로 시작한다.")
  void issueUpload_keyCarriesOwner() {
    long mine = aUser().insert(jdbcTemplate);
    long other = aUser().insert(jdbcTemplate);

    String myKey =
        profileImageService
            .issueUpload(new ProfileImageUploadCommand(mine, WEBP, ONE_MB))
            .objectKey();

    assertThat(myKey).doesNotStartWith("profile/" + other + "/");
  }

  /** 같은 주소를 덮어쓰면 브라우저·CDN 캐시가 옛 이미지를 계속 보여준다. */
  @Test
  @DisplayName("발급할 때마다 객체 키가 다르다.")
  void issueUpload_keyIsUnique() {
    long userId = aUser().insert(jdbcTemplate);

    String first =
        profileImageService
            .issueUpload(new ProfileImageUploadCommand(userId, WEBP, ONE_MB))
            .objectKey();
    String second =
        profileImageService
            .issueUpload(new ProfileImageUploadCommand(userId, WEBP, ONE_MB))
            .objectKey();

    assertThat(first).isNotEqualTo(second);
  }

  /** 검증 기준의 「MIME 위반 400」이다. 올리기 전에 막는 것이 올린 뒤 버리는 것보다 낫다. */
  @Test
  @DisplayName("허용되지 않은 형식은 발급 단계에서 거부된다.")
  void issueUpload_disallowedType() {
    long userId = aUser().insert(jdbcTemplate);

    assertThatThrownBy(
            () ->
                profileImageService.issueUpload(
                    new ProfileImageUploadCommand(userId, "image/gif", ONE_MB)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorCode", UserErrorCode.USER_PROFILE_IMAGE_TYPE_NOT_ALLOWED);
  }

  /** 검증 기준의 「용량 위반 400」이다. */
  @Test
  @DisplayName("상한을 넘는 크기는 발급 단계에서 거부된다.")
  void issueUpload_tooLarge() {
    long userId = aUser().insert(jdbcTemplate);

    assertThatThrownBy(
            () ->
                profileImageService.issueUpload(
                    new ProfileImageUploadCommand(userId, WEBP, 50 * ONE_MB)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_PROFILE_IMAGE_TOO_LARGE);
  }

  @Test
  @DisplayName("확정하면 프로필 이미지 주소가 박힌다.")
  void confirm() {
    long userId = aUser().insert(jdbcTemplate);
    String objectKey = "profile/" + userId + "/a.webp";
    given(storage.findUploaded(objectKey)).willReturn(Optional.of(new UploadedImage(WEBP, ONE_MB)));

    profileImageService.confirm(userId, objectKey);

    assertThat(userRepository.findById(userId).orElseThrow().getProfileImageUrl())
        .isEqualTo("https://cdn.example/" + objectKey);
  }

  /**
   * <b>미리 박지 않는 이유가 이 테스트다.</b> 사용자가 업로드를 취소하면 그 주소가 없는 객체를 가리키고, 그것은 {@code null} 도 아니고 유효한 값도 아니라
   * 아바타가 깨진 채로 굳는다.
   */
  @Test
  @DisplayName("올라간 것이 없으면 거부되고 프로필도 그대로다.")
  void confirm_notUploaded() {
    long userId = aUser().insert(jdbcTemplate);
    String objectKey = "profile/" + userId + "/missing.webp";
    given(storage.findUploaded(objectKey)).willReturn(Optional.empty());

    assertThatThrownBy(() -> profileImageService.confirm(userId, objectKey))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_PROFILE_IMAGE_NOT_UPLOADED);
    assertThat(userRepository.findById(userId).orElseThrow().getProfileImageUrl()).isNull();
  }

  /**
   * <b>같은 코드로 답하는 것이 의도다.</b> 「그 키는 남의 것이다」로 답하면 남의 객체가 존재한다는 사실을 알려준다.
   *
   * <p>저장소에 물어보지도 않는다 — 접두어에서 이미 걸린다.
   */
  @Test
  @DisplayName("남의 객체 키로 확정하면 올린 것이 없다고 답한다.")
  void confirm_othersKey() {
    long mine = aUser().insert(jdbcTemplate);
    long other = aUser().insert(jdbcTemplate);
    String othersKey = "profile/" + other + "/stolen.webp";

    assertThatThrownBy(() -> profileImageService.confirm(mine, othersKey))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_PROFILE_IMAGE_NOT_UPLOADED);
    assertThat(userRepository.findById(mine).orElseThrow().getProfileImageUrl()).isNull();
  }

  /** 발급 때의 선언은 거짓말일 수 있다. 5MB 라 하고 50MB 를 올리면 이 층에서 걸린다. */
  @Test
  @DisplayName("실제로 올라간 크기가 상한을 넘으면 확정이 거부된다.")
  void confirm_actualTooLarge() {
    long userId = aUser().insert(jdbcTemplate);
    String objectKey = "profile/" + userId + "/huge.webp";
    given(storage.findUploaded(objectKey))
        .willReturn(Optional.of(new UploadedImage(WEBP, 50 * ONE_MB)));

    assertThatThrownBy(() -> profileImageService.confirm(userId, objectKey))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_PROFILE_IMAGE_TOO_LARGE);
    assertThat(userRepository.findById(userId).orElseThrow().getProfileImageUrl()).isNull();
  }

  @Test
  @DisplayName("실제로 올라간 형식이 허용되지 않으면 확정이 거부된다.")
  void confirm_actualDisallowedType() {
    long userId = aUser().insert(jdbcTemplate);
    String objectKey = "profile/" + userId + "/fake.webp";
    given(storage.findUploaded(objectKey))
        .willReturn(Optional.of(new UploadedImage("application/pdf", ONE_MB)));

    assertThatThrownBy(() -> profileImageService.confirm(userId, objectKey))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorCode", UserErrorCode.USER_PROFILE_IMAGE_TYPE_NOT_ALLOWED);
  }

  @Test
  @DisplayName("탈퇴한 계정은 확정할 수 없다.")
  void confirm_withdrawn() {
    long userId = aUser().status(SignupStatus.WITHDRAWN).insert(jdbcTemplate);
    String objectKey = "profile/" + userId + "/a.webp";
    given(storage.findUploaded(objectKey)).willReturn(Optional.of(new UploadedImage(WEBP, ONE_MB)));

    assertThatThrownBy(() -> profileImageService.confirm(userId, objectKey))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NOT_FOUND);
  }

  @Test
  @DisplayName("객체 키를 주지 않으면 올린 것이 없다고 답한다.")
  void confirm_nullKey() {
    long userId = aUser().insert(jdbcTemplate);

    assertThatThrownBy(() -> profileImageService.confirm(userId, null))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_PROFILE_IMAGE_NOT_UPLOADED);
  }
}
