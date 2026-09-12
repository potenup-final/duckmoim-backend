package com.duckmoim.chat.infra;

import com.duckmoim.chat.domain.ChatImageStorage;
import com.duckmoim.chat.domain.UploadedChatImage;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * {@link ChatImageStorage} 를 S3 로 구현한다 (CH-14 · CH-17).
 *
 * <p><b>파일이 이 클래스를 지나지 않는다.</b> 서버가 하는 일은 서명 · 확인 · 삭제이고 바이트는 브라우저와 S3 사이에서만 오간다 — EC2 한 대 구성이라 그
 * 차이가 크다 ({@code S3ProfileImageStorage} 가 같은 근거를 적어 두었다).
 *
 * <p><b>버킷과 SDK 빈을 프로필 이미지와 공유한다.</b> {@code S3Config} 의 {@code S3Client} · {@code S3Presigner} 를
 * 그대로 주입받고, 접두어만 {@code chat/} 이다 — 저장소 <b>포트</b>는 합치지 않았지만 <b>배선</b>은 합쳐져 있다 (계획서 8.1). 합칠 값이 있는
 * 것은 이쪽이고, 합치면 안 되는 것이 메서드 집합이다.
 *
 * <p><b>⚠️ 버킷 정책이 이 구현의 전제다.</b> 프로필 이미지가 공개 주소를 쓰므로 버킷에 공개 읽기 허용이 있는데, 그 {@code Resource} 가 {@code
 * 버킷/*} 이면 {@code chat/} 도 함께 공개된다 — 주소를 저장하지 않아도 <b>추측해서 남의 대화 사진을 볼 수 있다.</b> {@code profile/*} 로
 * 좁혀져 있어야 {@code CH-15} 가 성립한다. 코드로 확인할 수 없어 여기 적어 둔다.
 *
 * <p><b>자격증명을 받지 않는다.</b> SDK 기본 공급자가 EC2 인스턴스 역할을 읽는다. <b>{@code s3:DeleteObject} 가 그 역할에 필요하다</b>
 * — 프로필 이미지는 지운 적이 없어 없을 수 있다.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${duckmoim.s3.chat-bucket:}' != ''")
public class S3ChatImageStorage implements ChatImageStorage {

  private final S3Client s3Client;
  private final S3Presigner presigner;
  private final String bucket;
  private final Duration presignTtl;

  public S3ChatImageStorage(
      S3Client s3Client,
      S3Presigner presigner,
      @Value("${duckmoim.s3.chat-bucket}") String bucket,
      @Value("${duckmoim.s3.presign-ttl}") Duration presignTtl) {
    this.s3Client = s3Client;
    this.presigner = presigner;
    this.bucket = bucket;
    this.presignTtl = presignTtl;
  }

  /**
   * <b>{@code contentType} 을 서명에 넣는다.</b> 다른 형식으로 올리면 서명이 어긋나 S3 가 거절한다.
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
   * <b>없으면 빈 값이다 — 예외로 올리지 않는다.</b> 「아직 안 올렸다」는 정상 흐름이다. 사용자가 파일 선택을 취소한 뒤 확정을 부르면 그 상태가 된다.
   *
   * <p>권한 부족(403)도 「없음」으로 접힌다 — S3 는 객체 존재를 숨기려고 없는 키에도 403 을 줄 수 있다. 그래서 <b>로그로 갈라 둔다</b>: 없음은
   * 조용하고 그 밖의 실패는 ERROR 로 남긴다. 안 그러면 버킷 정책이 잘못됐을 때 「사용자가 안 올렸다」로만 보인다.
   */
  @Override
  public Optional<UploadedChatImage> findUploaded(String objectKey) {
    try {
      HeadObjectResponse head =
          s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());

      return Optional.of(new UploadedChatImage(head.contentType(), head.contentLength()));
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (S3Exception e) {
      log.error("[S3ChatImageStorage.findUploaded] 올라온 객체를 읽지 못했다. status={}", e.statusCode(), e);
      return Optional.empty();
    }
  }

  /**
   * <b>없는 키를 지워도 성공이다.</b> S3 의 {@code DeleteObject} 가 원래 멱등이다 — 배치가 중복으로 돌아도 둘째 호출이 터지지 않는다.
   *
   * <p><b>던지지 않는다.</b> 한 건의 실패가 그 주기의 나머지를 멈추면 고아 하나 때문에 정리가 영구히 막힌다. {@code false} 를 돌려 부르는 쪽이 행을
   * 남기게 하고, 다음 주기가 다시 집는다.
   *
   * <p><b>여기서 권한 부족이 드러난다.</b> 인스턴스 역할에 {@code s3:DeleteObject} 가 없으면 매 주기 {@code 403} 이 쌓인다 — 그래서
   * ERROR 로 남긴다. 조용히 실패하면 「지워지고 있다고 믿는」 상태가 된다.
   */
  @Override
  public boolean delete(String objectKey) {
    try {
      s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
      return true;
    } catch (S3Exception e) {
      log.error("[S3ChatImageStorage.delete] 객체를 지우지 못했다. status={}", e.statusCode(), e);
      return false;
    }
  }
}
