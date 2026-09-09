package com.duckmoim.identity.service;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.ProfileImagePolicy;
import com.duckmoim.identity.domain.ProfileImageStorage;
import com.duckmoim.identity.domain.UploadedImage;
import com.duckmoim.identity.domain.User;
import com.duckmoim.identity.exception.UserErrorCode;
import com.duckmoim.identity.infra.UserRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로필 이미지 업로드 (AU-08 · I 티켓).
 *
 * <p><b>경로가 둘이다</b> — 서명된 주소를 <b>발급</b>하고, 올라간 것을 <b>확정</b>한다. 파일이 서버를 지나지 않아서 한 번으로 끝나지 않는다.
 *
 * <p><b>왜 확정을 두는가</b> — 발급 시점에 최종 주소를 이미 알지만, 그때 박으면 사용자가 업로드를 취소했을 때 <b>없는 객체를 가리키는 주소</b>가 남는다.
 * 결정 D-2 가 세운 <i>"업로드하지 않은 유저는 null"</i> 이 깨지고, 삭제 엔드포인트가 계약에 없어 사용자가 되돌릴 수도 없다.
 *
 * <p><b>{@code UserService} 에 넣지 않았다.</b> 그쪽은 닉네임 유일성(I-01)의 409 변환을 한 곳에 모으려고 가입·프로필 수정을 함께 두고 있다.
 * 이미지는 그 제약과 무관하고 저장소 포트를 주입받아 성질이 다르다.
 */
@Service
public class ProfileImageService {

  /** 확정 요청이 남의 것을 들고 오지 못하게 하는 접두어다. 키 자체가 소유자를 말한다. */
  private static final String KEY_PREFIX = "profile/";

  private final UserRepository userRepository;
  private final ProfileImageStorage storage;
  private final ProfileImagePolicy policy;
  private final Duration presignTtl;

  public ProfileImageService(
      UserRepository userRepository,
      ProfileImageStorage storage,
      @Value("${duckmoim.profile-image.allowed-content-types}") List<String> allowedContentTypes,
      @Value("${duckmoim.profile-image.max-bytes}") long maxBytes,
      @Value("${duckmoim.s3.presign-ttl}") Duration presignTtl) {
    this.userRepository = userRepository;
    this.storage = storage;
    this.policy = new ProfileImagePolicy(allowedContentTypes, maxBytes);
    this.presignTtl = presignTtl;
  }

  /**
   * 서명된 업로드 주소를 발급한다.
   *
   * <p><b>여기가 검증 기준의 「용량·MIME 위반 400」이 나는 자리다.</b> 클라이언트가 선언한 값을 보고, 정책을 벗어나면 서명을 만들지 않는다 — 올리기 전에
   * 막는 것이 올린 뒤 버리는 것보다 낫다.
   *
   * <p><b>선언은 거짓말일 수 있다.</b> 5MB 라 하고 50MB 를 올리면 이 검사는 통과한다. 그래서 {@link #confirm} 이 실제 값을 다시 본다.
   *
   * <p><b>키에 UUID 를 넣는다.</b> 회원번호만으로 만들면 같은 주소를 덮어써서 브라우저·CDN 캐시가 옛 이미지를 계속 보여준다.
   *
   * <p>DB 를 건드리지 않아 트랜잭션이 없다. 회원이 있는지도 확인하지 않는다 — 등급이 {@code SIGNUP} 이라 관문이 이미 회원을 읽었다.
   */
  public ProfileImageUpload issueUpload(ProfileImageUploadCommand command) {
    policy.validate(command.contentType(), command.contentLength());

    String objectKey = objectKeyOf(command.userId(), command.contentType());

    return new ProfileImageUpload(
        storage.presignUpload(objectKey, command.contentType()), objectKey, presignTtl.toSeconds());
  }

  /**
   * 올라간 것을 확인하고 프로필에 박는다.
   *
   * <p><b>세 가지를 본다.</b> 키가 내 것인지 · 저장소에 실제로 있는지 · 실제 형식과 크기가 정책 안인지.
   *
   * <p><b>남의 키를 들고 오면 「올린 것이 없다」로 답한다.</b> 「남의 것이다」로 답하면 <b>그 객체가 존재한다는 사실</b>을 알려준다. 접두어 검사와 존재
   * 검사가 같은 에러 코드를 쓰는 이유다.
   *
   * @throws BusinessException 위 셋 중 하나라도 어긋나면 {@code USER_PROFILE_IMAGE_NOT_UPLOADED}, 실제 값이 정책을
   *     벗어나면 형식·크기별 코드
   */
  @Transactional
  public void confirm(Long userId, String objectKey) {
    UploadedImage uploaded =
        requireOwnedKey(userId, objectKey)
            .flatMap(storage::findUploaded)
            .orElseThrow(
                () -> new BusinessException(UserErrorCode.USER_PROFILE_IMAGE_NOT_UPLOADED));

    policy.validate(uploaded.contentType(), uploaded.contentLength());

    User user =
        userRepository
            .findByIdForUpdate(userId)
            .filter(found -> !found.isWithdrawn())
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

    user.updateProfileImage(storage.publicUrlOf(objectKey));
  }

  /** 키를 만드는 규칙 한 곳. 접두어 검사가 이 규칙을 되짚으므로 둘이 갈라지면 확정이 통째로 막힌다. */
  private String objectKeyOf(Long userId, String contentType) {
    return KEY_PREFIX + userId + "/" + UUID.randomUUID() + "." + policy.extensionOf(contentType);
  }

  private java.util.Optional<String> requireOwnedKey(Long userId, String objectKey) {
    return objectKey != null && objectKey.startsWith(KEY_PREFIX + userId + "/")
        ? java.util.Optional.of(objectKey)
        : java.util.Optional.empty();
  }
}
