package com.duckmoim.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3 클라이언트의 시간 상한 (CH-16 리뷰).
 *
 * <p>SDK 기본값에는 호출 전체의 상한이 없어, S3 가 느린 날 부른 스레드가 그대로 물린다. 빠지면 조용히 기본값으로 돌아가므로 설정된 값을 직접 읽어 본다.
 *
 * <p>클라이언트를 만드는 데는 네트워크도 자격증명도 필요 없다 — 자격증명은 첫 호출에서 찾는다.
 */
@DisplayName("S3 클라이언트 설정")
class S3ConfigTest {

  @DisplayName("시도 한 번과 호출 전체에 시간 상한이 걸린다.")
  @Test
  void s3Client_hasTimeouts() {
    try (S3Client client =
        new S3Config().s3Client("ap-northeast-2", Duration.ofSeconds(20), Duration.ofSeconds(60))) {

      ClientOverrideConfiguration config =
          client.serviceClientConfiguration().overrideConfiguration();
      assertThat(config.apiCallAttemptTimeout()).contains(Duration.ofSeconds(20));
      assertThat(config.apiCallTimeout()).contains(Duration.ofSeconds(60));
    }
  }
}
