package com.duckmoim.chat.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;

import com.duckmoim.chat.domain.ChatImage;
import com.duckmoim.chat.domain.ChatImageStatus;
import com.duckmoim.chat.domain.ChatImageStorage;
import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.StoredChatImage;
import com.duckmoim.chat.infra.ChatImageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.chat.infra.exif.ExifFixtures;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * EXIF 워커 (CH-16) — <b>좌표가 든 사진이 올라오면 저장소의 파일에서 좌표가 사라진다.</b>
 *
 * <p><b>저장소를 메모리 지도로 흉내 낸다.</b> 내려받기는 지도에서 꺼내고, 덮어쓰기는 ETag 가 맞을 때만 지도에 쓴다 — 실물 S3 의 조건부 PUT 과 같은
 * 규칙이다. 그래서 「저장된 파일에 EXIF 없음」을 <b>지도에 남은 바이트</b>로 판정한다.
 *
 * <p><b>{@code @Transactional} 로 가둔다.</b> 워커는 표 전체를 훑으므로 다른 검사가 남긴 행도 집는다 — 그래서 단언은 전부 이 검사가 만든 행과
 * 키로만 한다.
 */
@SpringBootTest
@Transactional
@DisplayName("EXIF 제거 워커")
class ChatImageExifWorkerTest {

  private static final LocalDateTime MEET_AT_UTC = LocalDateTime.of(2026, 10, 1, 9, 0);

  @Autowired private ChatImageExifWorker worker;
  @Autowired private ChatImageExifService chatImageExifService;
  @Autowired private ChatImageRepository chatImageRepository;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private ChatImageStorage storage;

  /** 키 → (바이트, ETag). 덮어쓰면 ETag 가 바뀐다. */
  private final Map<String, StoredChatImage> bucket = new HashMap<>();

  private long memberId;
  private long roomId;

  @BeforeEach
  void setUp() {
    long hostId = aUser().nickname("방장" + suffix()).insert(jdbcTemplate);
    memberId = aUser().nickname("멤버" + suffix()).insert(jdbcTemplate);
    long postId = aCompanionPost().hostId(hostId).meetAt(MEET_AT_UTC).insert(jdbcTemplate);

    ChatRoom room = ChatRoom.openFor(postId, hostId);
    room.invite(memberId);
    roomId = chatRoomRepository.saveAndFlush(room).getId();

    given(storage.download(anyString()))
        .willAnswer(
            invocation -> Optional.ofNullable(bucket.get(invocation.<String>getArgument(0))));
    willAnswer(
            invocation -> {
              String key = invocation.getArgument(0);
              StoredChatImage current = bucket.get(key);
              if (current == null || !current.etag().equals(invocation.getArgument(3))) {
                return false; // 412 · 404
              }
              bucket.put(
                  key,
                  new StoredChatImage(
                      invocation.getArgument(1),
                      current.contentType(),
                      UUID.randomUUID().toString()));
              return true;
            })
        .given(storage)
        .overwriteIfUnchanged(anyString(), any(), anyString(), anyString());
  }

  // ── 검증 기준 ─────────────────────────────────────────────────────────────

  /**
   * <b>이 검사가 CH-16 의 검증 기준이다</b> — 좌표가 든 사진 업로드 후 저장된 파일에 EXIF 없음.
   *
   * <p>확정까지 마친 사진을 워커가 한 바퀴 돌리고, <b>저장소에 남은 바이트</b>에서 심어 둔 좌표 · 기종 · 촬영 시각이 사라졌는지 본다.
   */
  @DisplayName("확정된 사진의 저장된 파일에서 좌표가 사라지고 STRIPPED 가 된다.")
  @Test
  void worker_stripsGpsFromStoredFile() {
    Inserted image = upload(ChatImageStatus.CONFIRMED, ExifFixtures.jpegWithGps(6));

    worker.stripPendingImages();

    byte[] stored = bucket.get(image.objectKey()).bytes();
    assertThat(indexOf(stored, ExifFixtures.MAKE)).as("기종").isNegative();
    assertThat(indexOf(stored, ExifFixtures.DATE_TIME)).as("촬영 시각").isNegative();
    assertThat(indexOf(stored, gpsBytes())).as("GPS 좌표").isNegative();
    assertThat(exifStatusOf(image.id())).isEqualTo("STRIPPED");
    assertThat(chatImageRepository.findById(image.id()).orElseThrow().isExifStripped()).isTrue();
  }

  /** STAR-115 가 이미 배포돼 있어 메시지에 실린 사진에도 좌표가 남아 있을 수 있다. 그것도 벗긴다. */
  @DisplayName("이미 메시지에 실린 사진도 벗긴다.")
  @Test
  void worker_stripsAttachedImage() {
    Inserted image = upload(ChatImageStatus.ATTACHED, ExifFixtures.pngWithGps(6));

    worker.stripPendingImages();

    assertThat(indexOf(bucket.get(image.objectKey()).bytes(), gpsBytes())).isNegative();
    assertThat(exifStatusOf(image.id())).isEqualTo("STRIPPED");
  }

  /** 확정 전에는 올라왔는지조차 모르고, DELETING 은 고아 정리가 지우는 중이다. */
  @DisplayName("확정 전이거나 정리 중인 사진은 건드리지 않는다.")
  @Test
  void worker_skipsPendingAndDeleting() {
    Inserted pending = upload(ChatImageStatus.PENDING, ExifFixtures.jpegWithGps(6));
    Inserted deleting = upload(ChatImageStatus.DELETING, ExifFixtures.jpegWithGps(6));

    worker.stripPendingImages();

    then(storage).should(never()).download(pending.objectKey());
    then(storage).should(never()).download(deleting.objectKey());
    assertThat(exifStatusOf(pending.id())).isEqualTo("PENDING");
  }

  // ── 실패 ──────────────────────────────────────────────────────────────────

  /**
   * <b>벗길 수 없는 파일은 재시도 없이 FAILED 다.</b> 몇 번 해도 결과가 같고, 그 파일은 원본에 무엇이 남았는지 모르니 영구히 보여주면 안 된다.
   *
   * <p>DB 의 {@code content_type} 이 {@code image/jpeg} 여도 파일 앞 바이트가 아니면 이렇게 끝난다.
   */
  @DisplayName("JPEG · PNG · WEBP 가 아닌 파일은 재시도 없이 FAILED 가 된다.")
  @Test
  void worker_failsUnsupportedImmediately() {
    Inserted image =
        upload(
            ChatImageStatus.CONFIRMED,
            "GIF89a-not-an-allowed-format".getBytes(StandardCharsets.US_ASCII));

    worker.stripPendingImages();

    assertThat(exifStatusOf(image.id())).isEqualTo("FAILED");
    assertThat(chatImageRepository.findById(image.id()).orElseThrow().isExifStripped()).isFalse();
    then(storage)
        .should(never())
        .overwriteIfUnchanged(eq(image.objectKey()), any(), anyString(), anyString());
  }

  /** 네트워크 장애 한 번에 FAILED 로 굳히면 좌표 든 사진이 영구히 안 보인다. 백오프 뒤로 미룬다. */
  @DisplayName("일시적 실패는 PENDING 으로 남기고 다음 시도 시각을 뒤로 민다.")
  @Test
  void worker_retriesTransientFailure() {
    Inserted image = upload(ChatImageStatus.CONFIRMED, ExifFixtures.jpegWithGps(6));
    given(storage.download(image.objectKey())).willThrow(new IllegalStateException("S3 끊김"));

    worker.stripPendingImages();

    assertThat(exifStatusOf(image.id())).isEqualTo("PENDING");
    assertThat(attemptsOf(image.id())).isEqualTo(1);
    // 저장값이 UTC 다. LocalDateTime.now() 는 JVM 시간대(KST)라 비교하면 아홉 시간이 어긋난다.
    assertThat(nextAttemptOf(image.id())).isAfter(LocalDateTime.now(java.time.ZoneOffset.UTC));
  }

  /** 재시도를 다 쓰면 끝이 있어야 한다. 끝이 없으면 같은 사진을 영원히 내려받는다. */
  @DisplayName("재시도를 다 쓰면 FAILED 가 된다.")
  @Test
  void worker_failsAfterMaxAttempts() {
    Inserted image = upload(ChatImageStatus.CONFIRMED, ExifFixtures.jpegWithGps(6));
    given(storage.download(image.objectKey())).willThrow(new IllegalStateException("S3 끊김"));

    for (int i = 0; i < 3; i++) {
      makeDue(image.id());
      worker.stripPendingImages();
    }

    assertThat(exifStatusOf(image.id())).isEqualTo("FAILED");
  }

  /** 벗기기가 멱등이라, 이미 벗긴 파일은 다시 올리지 않고 표시만 한다. 기존 사진 일괄 처리 때 업로드를 아낀다. */
  @DisplayName("이미 벗겨진 파일은 덮어쓰지 않고 STRIPPED 로 표시만 한다.")
  @Test
  void worker_skipsOverwriteWhenAlreadyClean() throws Exception {
    byte[] clean =
        new com.duckmoim.chat.infra.exif.ImageMetadataStripper().strip(ExifFixtures.jpegWithGps(6));
    Inserted image = upload(ChatImageStatus.CONFIRMED, clean);

    worker.stripPendingImages();

    then(storage)
        .should(never())
        .overwriteIfUnchanged(eq(image.objectKey()), any(), anyString(), anyString());
    assertThat(exifStatusOf(image.id())).isEqualTo("STRIPPED");
  }

  /**
   * <b>덮어쓰기 조건이 어긋나면 STRIPPED 를 적지 않는다.</b> 그 사이 고아 정리가 지웠거나 다른 워커가 먼저 썼다.
   *
   * <p>조건 없이 쓰는 저장소였다면 지워진 객체를 되살렸을 자리다 ({@code S3ChatImageStorageTest} 가 If-Match 를 붙든다).
   */
  @DisplayName("내려받은 뒤 객체가 바뀌었으면 STRIPPED 를 적지 않는다.")
  @Test
  void worker_doesNotMarkWhenObjectChanged() {
    Inserted image = upload(ChatImageStatus.CONFIRMED, ExifFixtures.jpegWithGps(6));
    given(storage.download(image.objectKey()))
        .willAnswer(
            invocation -> {
              StoredChatImage current = bucket.get(image.objectKey());
              bucket.put(
                  image.objectKey(),
                  new StoredChatImage(current.bytes(), current.contentType(), "바뀐-etag"));
              return Optional.of(current);
            });

    worker.stripPendingImages();

    assertThat(exifStatusOf(image.id())).isEqualTo("PENDING");
  }

  // ── 선점과 전송 ────────────────────────────────────────────────────────────

  /** 인스턴스가 둘이라 워커도 둘이다. 한쪽이 집은 사진을 리스가 풀리기 전에 다른 쪽이 또 집으면 10MB 를 두 번 내려받는다. */
  @DisplayName("선점한 사진은 리스가 풀리기 전까지 다시 집히지 않는다.")
  @Test
  void claim_leasesClaimedImages() {
    Inserted image = upload(ChatImageStatus.CONFIRMED, ExifFixtures.jpegWithGps(6));
    LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);

    List<Long> first = chatImageExifService.claimProcessableIds(now, 1000);
    List<Long> second = chatImageExifService.claimProcessableIds(now, 1000);

    assertThat(first).contains(image.id());
    assertThat(second).doesNotContain(image.id());
  }

  /**
   * <b>워커가 벗겼다고 적어도 그 사이에 진행 중이던 전송이 깨지지 않는다.</b>
   *
   * <p>워커는 확정 직후, 사용자가 보내는 바로 그 몇 초 사이에 돈다. 두 가지가 동시에 막혀야 한다.
   *
   * <pre>
   * 워커가 버전을 올리면          → 전송의 attach 가 버전 충돌로 400      (정상 사용자가 맞는다)
   * 엔티티가 모든 열을 다시 쓰면   → 전송이 읽어 둔 PENDING 으로 STRIPPED 를 지운다
   * </pre>
   *
   * <p><b>두 트랜잭션을 진짜로 가른다.</b> 한 영속성 컨텍스트 안에서 흉내 내면 벌크 UPDATE 의 {@code clearAutomatically} 가 전송의
   * 엔티티를 떼어 내고, 그다음 {@code merge} 가 낡은 값을 통째로 복사해 운영과 다른 경로를 검사하게 된다. 그래서 이 검사만 클래스의 트랜잭션에서 빠져 실제로
   * 커밋한다.
   */
  @DisplayName("워커의 표시와 겹친 전송은 성공하고 STRIPPED 도 지워지지 않는다.")
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void markStripped_doesNotBreakConcurrentAttach() {
    Inserted image = upload(ChatImageStatus.CONFIRMED, ExifFixtures.jpegWithGps(6));
    TransactionTemplate send = new TransactionTemplate(transactionManager);
    TransactionTemplate workerTx = new TransactionTemplate(transactionManager);
    workerTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    try {
      send.executeWithoutResult(
          sendStatus -> {
            ChatImage readBySend = chatImageRepository.findById(image.id()).orElseThrow();

            // 전송이 읽은 뒤, 다른 트랜잭션의 워커가 벗겼다고 적고 커밋한다.
            workerTx.executeWithoutResult(w -> chatImageExifService.markStripped(image.id()));

            readBySend.attach();
            chatImageRepository.saveAndFlush(readBySend);
          });

      assertThat(statusOf(image.id())).isEqualTo("ATTACHED");
      assertThat(exifStatusOf(image.id())).isEqualTo("STRIPPED");
    } finally {
      jdbcTemplate.update("DELETE FROM chat_image WHERE id = ?", image.id());
    }
  }

  // ── 도구 ──────────────────────────────────────────────────────────────────

  private record Inserted(long id, String objectKey) {}

  private Inserted upload(ChatImageStatus status, byte[] bytes) {
    String objectKey = "chat/" + roomId + "/" + UUID.randomUUID() + ".jpg";
    jdbcTemplate.update(
        """
        INSERT INTO chat_image (room_id, uploader_id, object_key, content_type, byte_size, status,
                                created_at, updated_at)
        VALUES (?, ?, ?, 'image/jpeg', ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
        """,
        roomId,
        memberId,
        objectKey,
        bytes.length,
        status.name());
    bucket.put(objectKey, new StoredChatImage(bytes, "image/jpeg", UUID.randomUUID().toString()));

    long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM chat_image WHERE object_key = ?", Long.class, objectKey);
    return new Inserted(id, objectKey);
  }

  private void makeDue(long imageId) {
    jdbcTemplate.update(
        "UPDATE chat_image SET exif_next_attempt_at = '2000-01-01' WHERE id = ?", imageId);
  }

  private String statusOf(long imageId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM chat_image WHERE id = ?", String.class, imageId);
  }

  private String exifStatusOf(long imageId) {
    return jdbcTemplate.queryForObject(
        "SELECT exif_status FROM chat_image WHERE id = ?", String.class, imageId);
  }

  private int attemptsOf(long imageId) {
    return jdbcTemplate.queryForObject(
        "SELECT exif_attempts FROM chat_image WHERE id = ?", Integer.class, imageId);
  }

  private LocalDateTime nextAttemptOf(long imageId) {
    return jdbcTemplate.queryForObject(
        "SELECT exif_next_attempt_at FROM chat_image WHERE id = ?", LocalDateTime.class, imageId);
  }

  private static byte[] gpsBytes() {
    return ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(ExifFixtures.GPS_SECONDS_NUMERATOR)
        .array();
  }

  private static int indexOf(byte[] haystack, String needle) {
    return indexOf(haystack, needle.getBytes(StandardCharsets.ISO_8859_1));
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
