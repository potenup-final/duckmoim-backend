package com.duckmoim.chat.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.duckmoim.chat.domain.ChatImage;
import com.duckmoim.chat.domain.ChatImageStatus;
import com.duckmoim.chat.domain.ChatImageStorage;
import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.infra.ChatImageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고아 정리의 검증 기준 (CH-17) — <b>확정되지 않은 객체가 주기 뒤 사라진다.</b>
 *
 * <p><b>기준 시각을 인자로 받는 설계라 시계를 대역으로 둘 필요가 없다.</b> 「주기 뒤」를 증명하려면 오래된 행이 필요한데, 그것을 {@code created_at}
 * 을 직접 박아 만든다 — {@code BaseEntity} 가 저장 시점을 채우므로 엔티티로는 과거를 만들 수 없다.
 *
 * <p><b>저장소를 목으로 세운다.</b> 실물 S3 를 쓰면 이 검사가 자격증명에 달리고, 「삭제가 실패하면 행을 남긴다」는 경로는 실물로 만들기 어렵다.
 *
 * <p><b>{@code @Transactional} 로 가둔다.</b> 이 검사들은 <b>집은 수와 지운 수</b>를 세는데, 앞 검사가 남긴 행이 보이면 그 수가 어긋난다
 * — 청크 질의가 방으로 거르지 않기 때문이다 (배치는 방 전체를 훑는 것이 맞다). {@code deleteChunk} 가 이 트랜잭션에 참여하고 검사가 끝나면 함께
 * 되돌아간다.
 */
@SpringBootTest
@Transactional
@DisplayName("채팅 이미지 고아 정리")
class ChatImageCleanupServiceTest {

  private static final LocalDateTime MEET_AT_UTC = LocalDateTime.of(2026, 10, 1, 9, 0);
  private static final LocalDateTime LONG_AGO = LocalDateTime.of(2026, 1, 1, 0, 0);
  private static final LocalDateTime THRESHOLD = LocalDateTime.of(2026, 6, 1, 0, 0);

  @Autowired private ChatImageCleanupService chatImageCleanupService;
  @Autowired private ChatImageRepository chatImageRepository;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private ChatImageStorage storage;

  private long hostId;
  private long roomId;

  @BeforeEach
  void setUp() {
    hostId = aUser().nickname("방장" + suffix()).insert(jdbcTemplate);
    long postId = aCompanionPost().hostId(hostId).meetAt(MEET_AT_UTC).insert(jdbcTemplate);
    roomId = chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, hostId)).getId();
  }

  /** <b>검증 기준.</b> 올리다 만 객체 — 행이 발급 시점에 생기기 때문에 배치가 이것을 볼 수 있다. */
  @DisplayName("오래된 PENDING 이미지는 저장소와 표에서 사라진다.")
  @Test
  void deleteChunk_removesStalePending() {
    given(storage.delete(anyString())).willReturn(true);
    long imageId = insertOld(ChatImageStatus.PENDING);

    ChatImageCleanup cleanup = chatImageCleanupService.deleteChunk(THRESHOLD, 100);

    assertThat(cleanup.deleted()).isEqualTo(1);
    assertThat(chatImageRepository.findById(imageId)).isEmpty();
  }

  /** <b>검증 기준.</b> 확정까지 갔지만 안 보낸 것 — 두 종류를 함께 집는다. */
  @DisplayName("오래된 CONFIRMED 이미지도 사라진다.")
  @Test
  void deleteChunk_removesStaleConfirmed() {
    given(storage.delete(anyString())).willReturn(true);
    long imageId = insertOld(ChatImageStatus.CONFIRMED);

    chatImageCleanupService.deleteChunk(THRESHOLD, 100);

    assertThat(chatImageRepository.findById(imageId)).isEmpty();
  }

  /** <b>여기가 가장 중요한 한 줄이다.</b> 실려 있는 사진을 지우면 말풍선이 없는 객체를 가리키고 되돌릴 길이 없다. */
  @DisplayName("메시지에 실린 이미지는 오래되어도 건드리지 않는다.")
  @Test
  void deleteChunk_keepsAttached() {
    long imageId = insertOld(ChatImageStatus.ATTACHED);

    ChatImageCleanup cleanup = chatImageCleanupService.deleteChunk(THRESHOLD, 100);

    assertThat(cleanup.picked()).isZero();
    assertThat(chatImageRepository.findById(imageId)).isPresent();
  }

  /** 방금 올린 사진을 지우면 정상 사용자가 전송에서 400 을 본다 — 보관 기간이 있는 이유다. */
  @DisplayName("기준 시각보다 최근이면 집지 않는다.")
  @Test
  void deleteChunk_keepsRecent() {
    given(storage.delete(anyString())).willReturn(true);
    long imageId =
        chatImageRepository
            .saveAndFlush(ChatImage.pending(roomId, hostId, key(), "image/jpeg"))
            .getId();

    ChatImageCleanup cleanup = chatImageCleanupService.deleteChunk(THRESHOLD, 100);

    assertThat(cleanup.picked()).isZero();
    assertThat(chatImageRepository.findById(imageId)).isPresent();
  }

  /**
   * <b>저장소 삭제가 실패하면 행을 남긴다.</b>
   *
   * <p>행을 먼저 지우면 객체가 남고 <b>아무도 그것을 찾을 수 없다</b> — 행이 없으면 다음 주기가 그 키를 모른다. 반대 순서의 실패는 다음 주기가 정리한다.
   *
   * <p>인스턴스 역할에 {@code s3:DeleteObject} 가 없을 때의 증상이 정확히 이 모양이다.
   */
  @DisplayName("저장소 삭제가 실패하면 행을 남겨 다음 주기가 다시 집는다.")
  @Test
  void deleteChunk_keepsRowWhenStorageFails() {
    given(storage.delete(anyString())).willReturn(false);
    long imageId = insertOld(ChatImageStatus.PENDING);

    ChatImageCleanup cleanup = chatImageCleanupService.deleteChunk(THRESHOLD, 100);

    assertThat(cleanup.picked()).isEqualTo(1);
    assertThat(cleanup.deleted()).isZero();
    assertThat(chatImageRepository.findById(imageId)).isPresent();
  }

  /** 상한을 넘게 집으면 배치가 한 주기에 저장소 호출을 무한정 한다. */
  @DisplayName("한 청크는 상한만큼만 집는다.")
  @Test
  void deleteChunk_respectsLimit() {
    given(storage.delete(anyString())).willReturn(true);
    insertOld(ChatImageStatus.PENDING);
    insertOld(ChatImageStatus.PENDING);
    insertOld(ChatImageStatus.PENDING);

    assertThat(chatImageCleanupService.deleteChunk(THRESHOLD, 2).picked()).isEqualTo(2);
  }

  /** {@code BaseEntity} 가 저장 시점을 채우므로 과거는 직접 박아야 만들어진다. */
  private long insertOld(ChatImageStatus status) {
    String objectKey = key();
    jdbcTemplate.update(
        """
        INSERT INTO chat_image (room_id, uploader_id, object_key, content_type, byte_size, status,
                                created_at, updated_at)
        VALUES (?, ?, ?, 'image/jpeg', 0, ?, ?, ?)
        """,
        roomId,
        hostId,
        objectKey,
        status.name(),
        LONG_AGO,
        LONG_AGO);

    return jdbcTemplate.queryForObject(
        "SELECT id FROM chat_image WHERE object_key = ?", Long.class, objectKey);
  }

  private String key() {
    return "chat/" + roomId + "/" + UUID.randomUUID() + ".jpg";
  }

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
