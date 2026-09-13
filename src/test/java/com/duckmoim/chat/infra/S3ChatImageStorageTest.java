package com.duckmoim.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 서명된 업로드 주소가 <b>무엇을 묶는가</b> (CH-14 · PR #147 리뷰).
 *
 * <p><b>이 검사가 지키는 것은 SDK 의 동작이다.</b> {@code PutObjectRequest#contentLength} 를 세웠을 때 {@code
 * Content-Length} 가 서명 대상 헤더로 들어가는지는 SDK 판본에 따라 달랐다는 보고가 있다. 빠지면 코드는 그대로인데 <b>크기 제한이 조용히 사라진다</b> —
 * 그래서 실제로 발급된 URL 의 {@code X-Amz-SignedHeaders} 를 읽어 확인한다.
 *
 * <p><b>네트워크도 자격증명도 필요 없다.</b> 서명은 로컬 계산이라 가짜 키로도 URL 이 나온다.
 */
@DisplayName("채팅 이미지 서명")
class S3ChatImageStorageTest {

  private final S3Presigner presigner =
      S3Presigner.builder()
          .region(Region.AP_NORTHEAST_2)
          .credentialsProvider(
              StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIATEST", "secret")))
          .build();

  private final S3ChatImageStorage storage =
      new S3ChatImageStorage(
          mock(S3Client.class), presigner, "duckmoim-chat-image", Duration.ofMinutes(5));

  @AfterEach
  void close() {
    presigner.close();
  }

  /**
   * <b>선언한 크기가 서명에 들어간다.</b> 다른 크기로 PUT 하면 S3 가 {@code SignatureDoesNotMatch} 로 거절한다.
   *
   * <p>빠지면 방 멤버 한 명이 「발급 → 대용량 PUT」을 반복해 버킷에 원하는 만큼 쓸 수 있다 — 채팅은 발급마다 새 키라 총량을 묶는 것이 없다.
   */
  @DisplayName("업로드 서명에 content-length 가 묶인다.")
  @Test
  void presignUpload_signsContentLength() {
    String url = storage.presignUpload("chat/3/abc.jpg", "image/jpeg", 204_800L);

    assertThat(signedHeaders(url)).contains("content-length");
  }

  /** 형식도 여전히 묶인다. 크기를 더하다가 형식이 빠지면 안 된다. */
  @DisplayName("업로드 서명에 content-type 이 묶인다.")
  @Test
  void presignUpload_signsContentType() {
    String url = storage.presignUpload("chat/3/abc.jpg", "image/jpeg", 204_800L);

    assertThat(signedHeaders(url)).contains("content-type");
  }

  private static List<String> signedHeaders(String url) {
    String query = URI.create(url).getRawQuery();

    return Arrays.stream(query.split("&"))
        .filter(pair -> pair.startsWith("X-Amz-SignedHeaders="))
        .map(
            pair ->
                URLDecoder.decode(pair.substring(pair.indexOf('=') + 1), StandardCharsets.UTF_8))
        .flatMap(value -> Arrays.stream(value.split(";")))
        .toList();
  }
}
