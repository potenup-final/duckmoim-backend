package com.duckmoim.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.identity.domain.ProfileImageStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 버킷 설정값에 따라 어느 구현이 뜨는지 (AU-08).
 *
 * <p><b>PR #89 리뷰가 잡은 결함의 회귀 테스트다.</b> 조건이 {@code @ConditionalOnProperty} 였을 때 <b>빈 문자열을 「값이 있다」로
 * 읽어</b> 로컬에서도 S3 구현이 떴고, 대역은 한 번도 뜨지 않았다. 자격증명이 없어도 빈 생성은 되므로 기동은 성공하고 <b>업로드만 {@code
 * SdkClientException} 으로 죽었다</b> — 조용해서 아무도 몰랐다.
 *
 * <p><b>{@code @SpringBootTest} 를 쓰지 않는다.</b> 이 검사에 필요한 것은 조건 판정뿐인데 그쪽은 DB 컨테이너까지 띄운다. {@code
 * ApplicationContextRunner} 가 같은 조건 평가를 그대로 돌리면서 속성만 갈아 끼운다 — 한 테스트에서 두 값을 다 볼 수 있는 유일한 방법이기도 하다.
 */
@DisplayName("프로필 이미지 저장소 배선")
class ProfileImageStorageWiringTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          // 러너는 맨 컨텍스트라 부트의 변환기가 없다. `5m` 같은 Duration 표기가 실제 앱과 같게
          // 풀리도록 붙인다 — 없으면 배선이 아니라 변환에서 죽어 검사가 헛돈다.
          .withInitializer(
              context ->
                  context
                      .getBeanFactory()
                      .setConversionService(ApplicationConversionService.getSharedInstance()))
          .withUserConfiguration(
              S3Config.class, S3ProfileImageStorage.class, StubProfileImageStorage.class);

  @Test
  @DisplayName("버킷 이름이 비면 대역이 뜬다.")
  void stubWhenBucketIsBlank() {
    runner
        .withPropertyValues("duckmoim.s3.bucket=")
        .run(
            context -> {
              assertThat(context).hasSingleBean(ProfileImageStorage.class);
              assertThat(context).doesNotHaveBean(S3ProfileImageStorage.class);
              // 클라이언트도 함께 물러난다. 남으면 자격증명 없는 환경에서 죽은 빈이 된다.
              assertThat(context).doesNotHaveBean(S3Config.class);
            });
  }

  @Test
  @DisplayName("버킷 이름을 설정하지 않아도 대역이 뜬다.")
  void stubWhenBucketIsMissing() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(ProfileImageStorage.class);
          assertThat(context).doesNotHaveBean(S3ProfileImageStorage.class);
        });
  }

  @Test
  @DisplayName("버킷 이름이 있으면 S3 구현이 뜨고 대역은 물러난다.")
  void s3WhenBucketIsSet() {
    runner
        .withPropertyValues(
            "duckmoim.s3.bucket=duckmoim-profile-image",
            "duckmoim.s3.region=ap-northeast-2",
            "duckmoim.s3.public-base-url=https://example.invalid",
            "duckmoim.s3.presign-ttl=5m")
        .run(
            context -> {
              assertThat(context).hasSingleBean(ProfileImageStorage.class);
              assertThat(context).hasSingleBean(S3ProfileImageStorage.class);
              assertThat(context.getBean(ProfileImageStorage.class))
                  .isInstanceOf(S3ProfileImageStorage.class);
            });
  }
}
