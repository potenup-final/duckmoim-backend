package com.duckmoim.identity.infra;

import com.duckmoim.identity.domain.ProfileImageStorage;
import com.duckmoim.identity.domain.UploadedImage;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * S3 로 프로필 이미지를 올리게 한다 (AU-08).
 *
 * <p><b>파일이 이 클래스를 지나지 않는다.</b> 서버가 하는 일은 서명과 확인 둘이고, 바이트는 브라우저와 S3 사이에서만 오간다. EC2 한 대에 애플리케이션이 올라간
 * 구성이라 그 차이가 크다 — 멀티파트였다면 동시 업로드가 그대로 힙과 대역폭을 먹는다.
 *
 * <p><b>버킷 이름이 설정돼 있을 때만 뜬다.</b> 없으면 {@code StubProfileImageStorage} 가 대신 뜬다. 프로파일로 가르지 않은 이유는 그쪽
 * javadoc 에 있다.
 *
 * <p><b>자격증명을 받지 않는다.</b> SDK 기본 공급자가 EC2 인스턴스 역할을 읽는다 — {@code ci-cd.yml} 이 ECR 로그인을 같은 방식으로 하고
 * <i>"ECR 자격증명은 서버에 두지 않는다"</i> 로 근거를 적어 두었다. 액세스 키를 환경변수로 넣지 않는다.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${duckmoim.s3.bucket:}' != ''")
public class S3ProfileImageStorage implements ProfileImageStorage {

  private final S3Client s3Client;
  private final S3Presigner presigner;
  private final String bucket;
  private final String publicBaseUrl;
  private final Duration presignTtl;

  public S3ProfileImageStorage(
      S3Client s3Client,
      S3Presigner presigner,
      @Value("${duckmoim.s3.bucket}") String bucket,
      @Value("${duckmoim.s3.public-base-url}") String publicBaseUrl,
      @Value("${duckmoim.s3.presign-ttl}") Duration presignTtl) {
    this.s3Client = s3Client;
    this.presigner = presigner;
    this.bucket = bucket;
    this.publicBaseUrl = publicBaseUrl;
    this.presignTtl = presignTtl;
  }

  /**
   * <b>{@code contentType} 을 서명에 넣는다.</b> 클라이언트가 다른 형식으로 올리면 서명이 어긋나 S3 가 거절한다.
   *
   * <p>그 층에만 의존하지 않는다 — 확정 단계가 {@link #findUploaded} 로 <b>실제 값</b>을 다시 본다. 크기는 서명에 묶기 어려워서 실제 검사가
   * 그쪽에 있다.
   */
  @Override
  public String presignUpload(String objectKey, String contentType) {
    PutObjectRequest put =
        PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType(contentType).build();

    return presigner
        .presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(presignTtl)
                .putObjectRequest(put)
                .build())
        .url()
        .toString();
  }

  /**
   * <b>없으면 빈 값이다 — 예외로 올리지 않는다.</b> 「아직 안 올렸다」는 정상 흐름이고, 사용자가 파일 선택을 취소한 뒤 확정을 부르면 그 상태가 된다.
   *
   * <p>권한 부족(403)도 「없음」으로 접힌다 — S3 는 객체 존재를 숨기려고 없는 키에도 403 을 줄 수 있다. 그래서 <b>로그로 갈라 둔다</b>: 없음은
   * 조용하고 그 밖의 실패는 ERROR 로 남긴다. 안 그러면 버킷 정책이 잘못됐을 때 「사용자가 안 올렸다」로만 보인다.
   */
  @Override
  public Optional<UploadedImage> findUploaded(String objectKey) {
    try {
      HeadObjectResponse head =
          s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());

      return Optional.of(new UploadedImage(head.contentType(), head.contentLength()));
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (S3Exception e) {
      log.error(
          "[S3ProfileImageStorage.findUploaded] Failed to read the uploaded object. status={}",
          e.statusCode(),
          e);
      return Optional.empty();
    }
  }

  /** 저장되는 값이다. 앞부분이 설정이라 CloudFront 를 세우면 이 값만 바뀐다. */
  @Override
  public String publicUrlOf(String objectKey) {
    return publicBaseUrl.replaceAll("/+$", "") + "/" + objectKey;
  }
}
