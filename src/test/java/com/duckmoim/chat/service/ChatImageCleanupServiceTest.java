package com.duckmoim.chat.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.duckmoim.chat.domain.ChatImage;
import com.duckmoim.chat.domain.ChatImageStatus;
import com.duckmoim.chat.domain.ChatImageStorage;
import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatImageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고아 정리의 검증 기준 (CH-17) — <b>확정되지 않은 객체가 주기 뒤 사라진다.</b> 그리고 <b>붙은 사진은 무슨 일이 있어도 안 지운다.</b>
 *
 * <p><b>배치를 직접 부른다.</b> 「못박기 → S3 삭제 → 행 삭제」 순서가 이 기능의 본체라 (PR #147 리뷰), 서비스 메서드 하나만 부르면 그 순서를 검사하지
 * 못한다.
 *
 * <p><b>과거는 직접 박는다.</b> {@code BaseEntity} 가 저장 시점을 채우므로 엔티티로는 24시간 전을 만들 수 없다.
 *
 * <p><b>저장소를 목으로 세운다.</b> 실물 S3 를 쓰면 검사가 자격증명에 달리고, 「삭제가 실패하면 행을 남긴다」는 경로를 만들기 어렵다.
 *
 * <p><b>{@code @Transactional} 로 가둔다.</b> 배치는 방을 가리지 않고 표 전체를 훑으므로, 앞 검사가 남긴 행이 보이면 호출 여부 단언이 어긋난다.
 */
@SpringBootTest
@Transactional
@DisplayName("채팅 이미지 고아 정리")
class ChatImageCleanupServiceTest {

  private static final LocalDateTime MEET_AT_UTC = LocalDateTime.of(2026, 10, 1, 9, 0);
  private static final LocalDateTime LONG_AGO = LocalDateTime.of(2026, 1, 1, 0, 0);
  private static final LocalDateTime THRESHOLD = LocalDateTime.of(2026, 6, 1, 0, 0);

  @Autowired private ChatImageCleanupBatch chatImageCleanupBatch;
  @Autowired private ChatImageCleanupService chatImageCleanupService;
  @Autowired private ChatImageRepository chatImageRepository;
  @Autowired private ChatImageService chatImageService;
  @Autowired private ChatMessageSendService chatMessageSendService;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private ChatImageStorage storage;

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
  }

  // ── 검증 기준 ─────────────────────────────────────────────────────────────

  /** <b>검증 기준.</b> 올리다 만 객체 — 발급 시점에 행을 만들어 둔 덕에 배치가 이것을 볼 수 있다. */
  @DisplayName("오래된 PENDING 이미지는 저장소와 표에서 사라진다.")
  @Test
  void batch_removesStalePending() {
    given(storage.delete(anyString())).willReturn(true);
    Inserted image = insertOld(ChatImageStatus.PENDING);

    chatImageCleanupBatch.deleteOrphanImages();

    then(storage).should().delete(image.objectKey());
    assertThat(chatImageRepository.findById(image.id())).isEmpty();
  }

  /** <b>검증 기준.</b> 확정까지 갔지만 안 보낸 것 — 두 종류를 함께 집는다. */
  @DisplayName("오래된 CONFIRMED 이미지도 사라진다.")
  @Test
  void batch_removesStaleConfirmed() {
    given(storage.delete(anyString())).willReturn(true);
    Inserted image = insertOld(ChatImageStatus.CONFIRMED);

    chatImageCleanupBatch.deleteOrphanImages();

    assertThat(chatImageRepository.findById(image.id())).isEmpty();
  }

  /** 실려 있는 사진을 지우면 말풍선이 없는 객체를 가리키고 되돌릴 길이 없다. */
  @DisplayName("메시지에 실린 이미지는 오래되어도 저장소를 건드리지 않는다.")
  @Test
  void batch_keepsAttached() {
    Inserted image = insertOld(ChatImageStatus.ATTACHED);

    chatImageCleanupBatch.deleteOrphanImages();

    then(storage).should(never()).delete(image.objectKey());
    assertThat(statusOf(image.id())).isEqualTo("ATTACHED");
  }

  /** 방금 올린 사진을 지우면 정상 사용자가 전송에서 400 을 본다 — 보관 기간이 있는 이유다. */
  @DisplayName("기준 시각보다 최근이면 집지 않는다.")
  @Test
  void claimChunk_skipsRecent() {
    chatImageRepository.saveAndFlush(ChatImage.pending(roomId, memberId, key(), "image/jpeg"));

    assertThat(chatImageCleanupService.claimChunk(THRESHOLD, 100).claimed()).isEmpty();
  }

  /** 상한을 넘게 집으면 한 주기에 저장소 호출을 무한정 한다. */
  @DisplayName("한 청크는 상한만큼만 집는다.")
  @Test
  void claimChunk_respectsLimit() {
    insertOld(ChatImageStatus.PENDING);
    insertOld(ChatImageStatus.PENDING);
    insertOld(ChatImageStatus.PENDING);

    assertThat(chatImageCleanupService.claimChunk(THRESHOLD, 2).picked()).isEqualTo(2);
  }

  // ── 저장소 실패 ────────────────────────────────────────────────────────────

  /**
   * <b>저장소 삭제가 실패하면 행을 {@code DELETING} 으로 남긴다.</b>
   *
   * <p>행을 지우면 객체 키를 되찾을 길이 없다. 인스턴스 역할에 {@code s3:DeleteObject} 가 없을 때의 증상이 정확히 이 모양이다.
   */
  @DisplayName("저장소 삭제가 실패하면 행이 DELETING 으로 남는다.")
  @Test
  void batch_keepsRowAsDeletingWhenStorageFails() {
    given(storage.delete(anyString())).willReturn(false);
    Inserted image = insertOld(ChatImageStatus.PENDING);

    chatImageCleanupBatch.deleteOrphanImages();

    assertThat(statusOf(image.id())).isEqualTo("DELETING");
  }

  /** 앞 주기가 못 지운 것을 다음 주기가 이어 받는다. 후보 질의가 {@code DELETING} 까지 집는 이유다. */
  @DisplayName("DELETING 으로 남은 행은 다음 주기에 다시 지워진다.")
  @Test
  void batch_retriesDeletingRowOnNextCycle() {
    Inserted image = insertOld(ChatImageStatus.PENDING);

    given(storage.delete(anyString())).willReturn(false);
    chatImageCleanupBatch.deleteOrphanImages();

    given(storage.delete(anyString())).willReturn(true);
    chatImageCleanupBatch.deleteOrphanImages();

    assertThat(chatImageRepository.findById(image.id())).isEmpty();
  }

  // ── 전송과 겹칠 때 (PR #147 리뷰) ─────────────────────────────────────────

  /**
   * <b>후보로 읽은 뒤에 붙은 사진은 못박히지 않는다.</b>
   *
   * <p>리뷰가 짚은 창이다 — 배치가 후보를 읽고, 그 사이에 전송이 사진을 붙이고 커밋한다. 처음 구현은 읽어 둔 엔티티의 상태(메모리 스냅샷)로 판정해서 이 사진의 S3
   * 객체를 지웠다. 지금은 조건부 UPDATE 가 <b>쓰는 순간의 상태</b>를 보므로 빠진다.
   *
   * <p>서비스 안에서 두 걸음 사이를 끼울 수 없어, 그 두 걸음을 저장소 메서드로 나눠 부른다.
   */
  @DisplayName("후보로 읽은 뒤에 첨부된 이미지는 DELETING 으로 못박히지 않는다.")
  @Test
  void claim_skipsImageAttachedAfterBeingRead() {
    Inserted image = insertOld(ChatImageStatus.CONFIRMED);
    List<Long> candidates = List.of(image.id());

    // 그 사이에 전송이 붙이고 커밋했다.
    jdbcTemplate.update("UPDATE chat_image SET status = 'ATTACHED' WHERE id = ?", image.id());

    assertThat(chatImageRepository.claimForDeletion(candidates)).isZero();
    assertThat(chatImageRepository.findByIdInAndStatus(candidates, ChatImageStatus.DELETING))
        .isEmpty();
    assertThat(statusOf(image.id())).isEqualTo("ATTACHED");
  }

  /**
   * <b>배치가 먼저 못박으면, 그 전에 읽어 둔 낡은 상태로는 덮어쓸 수 없다.</b>
   *
   * <p>{@code @Version} 이 없으면 전송이 메모리의 {@code CONFIRMED} 로 판정하고 {@code ATTACHED} 를 써서 {@code
   * DELETING} 을 덮는다. 그 뒤 배치가 객체를 지우면 <b>붙은 사진이 영원히 깨진다.</b> 벌크 UPDATE 가 버전을 함께 올리지 않아도 같은 일이 난다.
   */
  @DisplayName("배치가 먼저 못박은 이미지는 낡은 상태로 첨부할 수 없다.")
  @Test
  void staleAttach_cannotOverwriteDeleting() {
    Inserted image = insertOld(ChatImageStatus.CONFIRMED);
    ChatImage readBeforeClaim = chatImageRepository.findById(image.id()).orElseThrow();

    chatImageRepository.claimForDeletion(List.of(image.id()));

    readBeforeClaim.attach();
    assertThatThrownBy(() -> chatImageRepository.saveAndFlush(readBeforeClaim))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThat(statusOf(image.id())).isEqualTo("DELETING");
  }

  /** 배치가 먼저면 전송은 400 이다. 24시간 넘은 고아라 「확인을 마친 이미지만 보낼 수 있다」가 맞는 답이다. */
  @DisplayName("배치가 못박은 이미지를 실어 보내면 400 이다.")
  @Test
  void send_rejectsImageClaimedByBatch() {
    Inserted image = insertOld(ChatImageStatus.CONFIRMED);
    chatImageCleanupService.claimChunk(THRESHOLD, 100);

    assertThatThrownBy(
            () ->
                chatMessageSendService.send(
                    roomId, memberId, UUID.randomUUID().toString(), "사진", image.id()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_IMAGE_NOT_CONFIRMED);
  }

  /** 확정도 같다. 못박힌 행을 {@code CONFIRMED} 로 되살리면 배치가 객체를 지운 뒤에도 전송이 그 사진을 붙인다. */
  @DisplayName("배치가 못박은 이미지는 확정할 수 없다.")
  @Test
  void confirm_rejectsImageClaimedByBatch() {
    Inserted image = insertOld(ChatImageStatus.PENDING);
    chatImageCleanupService.claimChunk(THRESHOLD, 100);

    assertThatThrownBy(() -> chatImageService.confirm(roomId, memberId, image.id()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_IMAGE_NOT_UPLOADED);
  }

  private record Inserted(long id, String objectKey) {}

  /** {@code BaseEntity} 가 저장 시점을 채우므로 과거는 직접 박아야 만들어진다. */
  private Inserted insertOld(ChatImageStatus status) {
    String objectKey = key();
    jdbcTemplate.update(
        """
        INSERT INTO chat_image (room_id, uploader_id, object_key, content_type, byte_size, status,
                                created_at, updated_at)
        VALUES (?, ?, ?, 'image/jpeg', 204800, ?, ?, ?)
        """,
        roomId,
        memberId,
        objectKey,
        status.name(),
        LONG_AGO,
        LONG_AGO);

    long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM chat_image WHERE object_key = ?", Long.class, objectKey);
    return new Inserted(id, objectKey);
  }

  private String statusOf(long imageId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM chat_image WHERE id = ?", String.class, imageId);
  }

  private String key() {
    return "chat/" + roomId + "/" + UUID.randomUUID() + ".jpg";
  }

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
