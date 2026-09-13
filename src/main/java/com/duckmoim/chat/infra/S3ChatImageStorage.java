package com.duckmoim.chat.infra;

import com.duckmoim.chat.domain.ChatImageStorage;
import com.duckmoim.chat.domain.StoredChatImage;
import com.duckmoim.chat.domain.UploadedChatImage;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
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
   * <b>{@code contentType} 과 {@code contentLength} 를 서명에 넣는다</b> (PR #147 리뷰).
   *
   * <p>{@code PutObjectRequest#contentLength} 를 세우면 {@code Content-Length} 가 {@code
   * X-Amz-SignedHeaders} 에 들어가, 선언과 다른 크기의 PUT 은 S3 가 {@code SignatureDoesNotMatch} 로 거절한다. <b>SDK
   * 판본에 따라 이 헤더가 서명에서 빠진다는 보고가 있어</b> 실제 발급 URL 을 확인했다 ({@code awssdk 2.29.52}):
   *
   * <pre>
   * contentLength 없음   X-Amz-SignedHeaders = content-type;host
   * contentLength 있음   X-Amz-SignedHeaders = content-length;content-type;host
   * </pre>
   *
   * <p>그 사실을 {@code S3ChatImageStorageTest} 가 붙들고 있다 — SDK 를 올려서 빠지면 빌드가 깨진다. 조용히 빠지면 크기 검사가 다시 「이미
   * 들어온 뒤」로 밀린다.
   *
   * <p><b>{@code If-None-Match: *} 도 서명에 넣는다 — 이 주소로는 한 번만 올릴 수 있다</b> (CH-16 리뷰). 서명 PUT 주소는 만료
   * 전까지 몇 번이든 쓸 수 있어서, 막지 않으면 EXIF 워커가 벗긴 뒤에 같은 주소로 원본을 다시 올릴 수 있었다.
   *
   * <pre>
   * 20:00:00  발급 (20:05 까지 유효)
   * 20:00:03  좌표 든 원본 PUT → 확정 → 전송
   * 20:00:10  워커가 벗기고 덮어씀                  exif = STRIPPED
   * 20:01:30  같은 주소로 원본을 다시 PUT          S3 = 좌표 든 원본  ⚠️  DB 는 STRIPPED 그대로
   *           → CH-15 가 isExifStripped() 를 믿고 서명 → 방 멤버에게 좌표가 나간다
   * </pre>
   *
   * <p>같은 크기면 <b>전송이 끝난 뒤 다른 사진으로 통째로 바꿔치기</b>도 됐다. 조건을 서명에 묶으면 헤더를 빼는 순간 서명이 어긋나고, 넣으면 객체가 이미 있을 때
   * S3 가 412 로 거절한다 — 워커의 덮어쓰기는 서명 주소가 아니라 서버 자격증명으로 {@code If-Match} 를 걸어 쓰므로 막히지 않는다.
   *
   * <p><b>클라이언트는 PUT 에 {@code If-None-Match: *} 를 함께 보내야 한다.</b> 버킷 CORS 의 허용 헤더에도 들어가야 한다. 같은 주소로
   * 두 번 올려 412 를 받으면 이미 올라간 것이니 확정으로 넘어가면 된다.
   *
   * <p><b>확정 단계의 실제 검사는 그대로 둔다.</b> 서명이 막는 것은 PUT 이고, 확정은 S3 가 기록한 값을 한 번 더 본다 — 두 층이 서로 다른 것을 믿는다.
   */
  @Override
  public String presignUpload(String objectKey, String contentType, long contentLength) {
    PutObjectRequest put =
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(objectKey)
            .contentType(contentType)
            .contentLength(contentLength)
            .ifNoneMatch("*")
            .build();

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

  /**
   * <b>없음만 빈 값이고 나머지 실패는 던진다.</b> {@code findUploaded} 가 권한 부족까지 「없음」으로 접는 것과 갈리는 자리다 — 그쪽은 사용자
   * 요청이라 400 으로 끝내는 것이 맞지만, 여기는 워커라 <b>일시 장애를 영구 실패로 오인하면 좌표가 남은 사진이 {@code FAILED} 로 굳는다.</b>
   */
  @Override
  public Optional<StoredChatImage> download(String objectKey) {
    try {
      ResponseBytes<GetObjectResponse> object =
          s3Client.getObjectAsBytes(
              GetObjectRequest.builder().bucket(bucket).key(objectKey).build());

      return Optional.of(
          new StoredChatImage(
              object.asByteArray(), object.response().contentType(), object.response().eTag()));
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    }
  }

  /**
   * {@code If-Match} 로 조건을 건다. 조건이 거짓이면 S3 가 <b>412</b>, 그 사이 지워졌으면 <b>404</b> 를 준다 — 둘 다 「재시도해도
   * 소용없음」이라 {@code false} 로 접는다. {@code awssdk 2.29.52} 의 {@code PutObjectRequest#ifMatch} 를 바이트코드로
   * 확인했다.
   */
  @Override
  public boolean overwriteIfUnchanged(
      String objectKey, byte[] bytes, String contentType, String etag) {
    try {
      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(objectKey)
              .contentType(contentType)
              .ifMatch(etag)
              .build(),
          RequestBody.fromBytes(bytes));
      return true;
    } catch (S3Exception e) {
      if (e.statusCode() == 412 || e.statusCode() == 404) {
        return false;
      }
      throw e;
    }
  }
}
