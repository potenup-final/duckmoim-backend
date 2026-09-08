package com.duckmoim.identity.infra;

import com.duckmoim.identity.domain.ProfileImageStorage;
import com.duckmoim.identity.domain.UploadedImage;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 버킷이 설정되지 않았을 때 뜨는 대역 (AU-08).
 *
 * <p><b>프로파일로 가르지 않은 이유</b> — {@code @Profile("prod")} 로 묶으면 자격증명을 가진 개발자가 <b>로컬에서 실물을 확인할 방법이
 * 없어진다.</b> 카카오 로그인(E)에서 실물 열쇠를 받아 로컬로 한 번 돌려본 것이 결정적이었는데, 그 경로를 스스로 막지 않는다. 버킷 이름을 채우면 S3 구현이 뜨고 이
 * 대역은 물러난다.
 *
 * <p><b>확인 요청에 빈 값을 답한다.</b> 「있다」고 답하면 확정이 통과해 <b>존재하지 않는 주소가 프로필에 박힌다</b> — 대역이 만드는 가장 나쁜 상태다. 그래서
 * 로컬에서 업로드를 끝까지 해보려면 버킷이 있어야 한다. 테스트는 이 대역을 쓰지 않고 포트를 목으로 세운다.
 */
@Slf4j
@Configuration
public class StubProfileImageStorage {

  @Bean
  @ConditionalOnMissingBean(ProfileImageStorage.class)
  public ProfileImageStorage stubProfileImageStorage() {
    log.warn(
        "[StubProfileImageStorage.stubProfileImageStorage] Bucket is not configured."
            + " Profile image upload is disabled.");

    return new ProfileImageStorage() {

      @Override
      public String presignUpload(String objectKey, String contentType) {
        return "https://profile-image-storage-is-not-configured.invalid/" + objectKey;
      }

      @Override
      public Optional<UploadedImage> findUploaded(String objectKey) {
        return Optional.empty();
      }

      @Override
      public String publicUrlOf(String objectKey) {
        return "https://profile-image-storage-is-not-configured.invalid/" + objectKey;
      }
    };
  }
}
