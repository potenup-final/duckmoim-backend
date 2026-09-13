package com.duckmoim.chat.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.duckmoim.chat.domain.ChatImageStatus;
import com.duckmoim.chat.domain.ChatImageStorage;
import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.MessageStatus;
import com.duckmoim.chat.infra.ChatImageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.companion.domain.ClosedReason;
import com.duckmoim.companion.domain.PostStatus;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * 보관과 파기의 검증 기준 (CH-19) — <b>두 번 돌아도 결과가 같다.</b> 그리고 <b>기간이 안 지난 방은 무슨 일이 있어도 안 지운다.</b>
 *
 * <p><b>배치를 직접 부른다.</b> 「사진 못박기 → 저장소 삭제 → 행 삭제 → 메시지 삭제」 순서가 이 기능의 본체라, 서비스 메서드 하나만 부르면 그 순서를 검사하지
 * 못한다 ({@code ChatImageCleanupServiceTest} 와 같은 판단이다).
 *
 * <p><b>경계와 상한은 서비스로 본다.</b> 배치는 기준 시각을 시계에서 읽어 「정확히 90일 되는 순간」을 짚을 수 없다 — 경계 시각을 인자로 받는 조회를 직접
 * 부른다.
 *
 * <p><b>과거는 직접 박는다.</b> 마감 시각이 90일 전인 모집글은 마감 경로로 만들 수 없다.
 *
 * <p><b>저장소를 목으로 세운다.</b> 실물 S3 를 쓰면 검사가 자격증명에 달리고, 「삭제가 실패하면 표시하지 않는다」는 경로를 만들기 어렵다.
 *
 * <p><b>{@code @Transactional} 로 가둔다.</b> 배치는 방을 가리지 않고 표 전체를 훑으므로, 앞 검사가 남긴 행이 보이면 단언이 어긋난다.
 */
@SpringBootTest
@Transactional
@DisplayName("채팅 보관 기간 파기")
class ChatPurgeServiceTest {

  /** 보관 기간(90일)보다 넉넉히 지난 마감. 시계가 실제 시각이라 상대값으로 잡는다. */
  private static final int LONG_PAST_DAYS = 200;

  /** 아직 보관 기간 안인 마감. */
  private static final int RECENT_DAYS = 30;

  /** 경계 검사용 고정 시각. 조회에 직접 넣는 값이라 시계와 무관하다. */
  private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 6, 1, 0, 0);

  /** 커서 없이 처음부터. 방 번호가 1부터라 0 이 그 뜻이다. */
  private static final long FROM_START = 0;

  private static final LocalDateTime MEET_AT_UTC = LocalDateTime.of(2026, 1, 1, 9, 0);

  @Autowired private ChatPurgeBatch chatPurgeBatch;
  @Autowired private ChatPurgeService chatPurgeService;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private ChatImageRepository chatImageRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private ChatImageStorage storage;

  private long hostId;
  private long memberId;

  @BeforeEach
  void setUp() {
    hostId = aUser().nickname("방장" + suffix()).insert(jdbcTemplate);
    memberId = aUser().nickname("멤버" + suffix()).insert(jdbcTemplate);
  }

  // ── 검증 기준 ─────────────────────────────────────────────────────────────

  /** <b>검증 기준.</b> 명세가 파기 대상으로 적은 둘 — 대화와 이미지. */
  @DisplayName("마감 후 90일이 지난 방의 대화와 사진이 사라진다.")
  @Test
  void batch_purgesExpiredRoom() {
    given(storage.delete(anyString())).willReturn(true);
    long roomId = expiredRoom();
    insertMessage(roomId, MessageStatus.ACTIVE);
    long imageId = insertImage(roomId, ChatImageStatus.ATTACHED);

    chatPurgeBatch.purgeExpiredRooms();

    assertThat(messageCountOf(roomId)).isZero();
    assertThat(chatImageRepository.findById(imageId)).isEmpty();
    assertThat(purgedAtOf(roomId)).isNotNull();
  }

  /**
   * <b>검증 기준 그 자체다</b> — 「두 번 돌아도 결과가 같다」.
   *
   * <p>두 번째 회차는 이 방을 아예 집지 않는다 ({@code purged_at} 이 찼다). 그래서 처음 파기한 시각이 마지막 실행 시각으로 밀리지 않는다.
   */
  @DisplayName("배치를 두 번 돌려도 결과가 같다.")
  @Test
  void batch_isIdempotent() {
    given(storage.delete(anyString())).willReturn(true);
    long roomId = expiredRoom();
    insertMessage(roomId, MessageStatus.ACTIVE);
    insertImage(roomId, ChatImageStatus.ATTACHED);

    chatPurgeBatch.purgeExpiredRooms();
    LocalDateTime firstPurgedAt = purgedAtOf(roomId);
    chatPurgeBatch.purgeExpiredRooms();

    assertThat(messageCountOf(roomId)).isZero();
    assertThat(imageCountOf(roomId)).isZero();
    assertThat(purgedAtOf(roomId)).isEqualTo(firstPurgedAt);
  }

  /** 지운 메시지도 블라인드된 메시지도 본문을 들고 있는 행이다 — 소프트 삭제가 감추는 것은 화면이지 데이터가 아니다. */
  @DisplayName("지운 메시지와 블라인드된 메시지도 함께 파기된다.")
  @Test
  void batch_purgesDeletedAndBlindedMessages() {
    long roomId = expiredRoom();
    insertMessage(roomId, MessageStatus.DELETED);
    insertMessage(roomId, MessageStatus.BLINDED);

    chatPurgeBatch.purgeExpiredRooms();

    assertThat(messageCountOf(roomId)).isZero();
  }

  /**
   * 명세가 파기 대상으로 적은 것은 「대화와 이미지」다.
   *
   * <p>방 행을 지우면 CH-01a 가 없앤 「방이 없는 경우」 분기가 조회 경로에 되살아난다 — 그 티켓이 기존 모집글에까지 방을 만들어 준 이유가 그 분기를 두지 않기
   * 위해서였다.
   */
  @DisplayName("파기한 뒤에도 방과 멤버는 남는다.")
  @Test
  void batch_keepsRoomAndMembers() {
    long roomId = expiredRoom();
    insertMessage(roomId, MessageStatus.ACTIVE);

    chatPurgeBatch.purgeExpiredRooms();

    ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow();
    assertThat(room.currentMembers()).hasSize(2);
  }

  // ── 집지 말아야 하는 것 ────────────────────────────────────────────────────

  /** 보관 기간이 남은 방을 지우면 고지한 기간보다 일찍 파기하는 것이다. */
  @DisplayName("마감 후 90일이 지나지 않은 방은 파기하지 않는다.")
  @Test
  void batch_keepsRoomWithinRetention() {
    long roomId = roomClosedDaysAgo(RECENT_DAYS);
    insertMessage(roomId, MessageStatus.ACTIVE);

    chatPurgeBatch.purgeExpiredRooms();

    assertThat(messageCountOf(roomId)).isOne();
    assertThat(purgedAtOf(roomId)).isNull();
  }

  /** 기준이 마감이라 아직 마감되지 않은 글의 방은 기산점 자체가 없다. */
  @DisplayName("열려 있는 모집글의 방은 파기하지 않는다.")
  @Test
  void batch_keepsRoomOfOpenPost() {
    long postId = aCompanionPost().hostId(hostId).meetAt(MEET_AT_UTC).insert(jdbcTemplate);
    long roomId = openRoom(postId);
    insertMessage(roomId, MessageStatus.ACTIVE);

    chatPurgeBatch.purgeExpiredRooms();

    assertThat(messageCountOf(roomId)).isOne();
    assertThat(purgedAtOf(roomId)).isNull();
  }

  /**
   * 경계 그 순간을 짚는다.
   *
   * <p>명세가 「마감 <i>후</i> 90일」이라 90일이 되는 순간은 아직 지난 것이 아니다. 부등호가 {@code <=} 로 뒤집히면 하루 일찍 지우는데, 다른 검사는
   * 전부 초록불이다.
   */
  @DisplayName("마감 시각이 기준과 정확히 같으면 파기 대상이 아니다.")
  @Test
  void findPurgeableRooms_keepsRoomClosedExactlyAtCutoff() {
    long roomId = roomClosedAt(CUTOFF);

    assertThat(chatPurgeService.findPurgeableRooms(CUTOFF, FROM_START, 100)).doesNotContain(roomId);
  }

  /** 상한을 넘게 집으면 한 주기가 저장소 호출을 무한정 한다. */
  @DisplayName("한 청크는 상한만큼만 집는다.")
  @Test
  void findPurgeableRooms_respectsLimit() {
    roomClosedAt(CUTOFF.minusDays(1));
    roomClosedAt(CUTOFF.minusDays(2));
    roomClosedAt(CUTOFF.minusDays(3));

    assertThat(chatPurgeService.findPurgeableRooms(CUTOFF, FROM_START, 2)).hasSize(2);
  }

  /**
   * 커서가 지나간 자리로 돌아가지 않는다 (PR #158 리뷰).
   *
   * <p>이것이 없으면 파기하지 못한 방이 <b>매 청크의 맨 앞자리를 계속 차지해</b> 뒤에 줄 선 방의 차례가 오지 않는다.
   */
  @DisplayName("커서를 주면 그 번호보다 큰 방만 집는다.")
  @Test
  void findPurgeableRooms_startsAfterCursor() {
    long first = roomClosedAt(CUTOFF.minusDays(1));
    long second = roomClosedAt(CUTOFF.minusDays(2));

    assertThat(chatPurgeService.findPurgeableRooms(CUTOFF, first, 100))
        .contains(second)
        .doesNotContain(first);
  }

  // ── 저장소 실패 ────────────────────────────────────────────────────────────

  /**
   * <b>저장소 삭제가 실패하면 파기를 표시하지 않는다.</b>
   *
   * <p>표시하면 그 방이 대상 목록에서 빠져 사진이 영영 남는다. 메시지는 그때도 지운다 — 지우는 것이 목적이고, 다음 주기에 이 방은 메시지 0건이라 값이 싸다.
   */
  @DisplayName("저장소 삭제가 실패하면 사진 행이 남고 파기 표시도 붙지 않는다.")
  @Test
  void batch_keepsRowWhenStorageFails() {
    given(storage.delete(anyString())).willReturn(false);
    long roomId = expiredRoom();
    insertMessage(roomId, MessageStatus.ACTIVE);
    long imageId = insertImage(roomId, ChatImageStatus.ATTACHED);

    chatPurgeBatch.purgeExpiredRooms();

    assertThat(statusOf(imageId)).isEqualTo("DELETING");
    assertThat(purgedAtOf(roomId)).isNull();
    assertThat(messageCountOf(roomId)).isZero();
  }

  /**
   * <b>앞 방의 실패가 뒤 방의 차례를 빼앗지 않는다</b> (PR #158 리뷰).
   *
   * <p>파기하지 못한 방은 표시가 붙지 않아 조건에 그대로 남는다. 커서가 없으면 그 방이 다음 조회의 맨 앞자리를 계속 차지하고, 그런 방이 청크 상한만큼 쌓이면 뒤에 줄
   * 선 정상 방은 영영 처리되지 않는다.
   *
   * <p><b>목을 키별로 갈라 세운다.</b> 앞 방의 사진만 실패시켜야 「앞이 막혀도 뒤가 간다」를 볼 수 있다.
   */
  @DisplayName("앞 방의 저장소 삭제가 실패해도 뒤 방은 같은 회차에 파기된다.")
  @Test
  void batch_purgesRoomBehindFailedRoom() {
    long blocked = expiredRoom();
    String blockedKey = insertImageKey(blocked);
    long following = expiredRoom();
    insertMessage(following, MessageStatus.ACTIVE);

    given(storage.delete(anyString())).willReturn(true);
    given(storage.delete(blockedKey)).willReturn(false);

    chatPurgeBatch.purgeExpiredRooms();

    assertThat(purgedAtOf(blocked)).isNull();
    assertThat(purgedAtOf(following)).isNotNull();
    assertThat(messageCountOf(following)).isZero();
  }

  /** 앞 주기가 못 지운 것을 다음 주기가 이어받는다. 그 방이 대상 목록에 남아 있어야 성립한다. */
  @DisplayName("저장소가 살아나면 다음 주기에 파기가 끝난다.")
  @Test
  void batch_retriesOnNextCycle() {
    long roomId = expiredRoom();
    long imageId = insertImage(roomId, ChatImageStatus.ATTACHED);

    given(storage.delete(anyString())).willReturn(false);
    chatPurgeBatch.purgeExpiredRooms();

    given(storage.delete(anyString())).willReturn(true);
    chatPurgeBatch.purgeExpiredRooms();

    assertThat(chatImageRepository.findById(imageId)).isEmpty();
    assertThat(purgedAtOf(roomId)).isNotNull();
  }

  // ── 픽스처 ────────────────────────────────────────────────────────────────

  /** 보관 기간이 지난 방. 멤버가 둘이고 (방장 + 초대) 모집글은 한참 전에 마감됐다. */
  private long expiredRoom() {
    return roomClosedDaysAgo(LONG_PAST_DAYS);
  }

  private long roomClosedDaysAgo(int days) {
    return roomClosedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(days));
  }

  private long roomClosedAt(LocalDateTime closedAtUtc) {
    long postId =
        aCompanionPost()
            .hostId(hostId)
            .meetAt(MEET_AT_UTC)
            .status(PostStatus.CLOSED)
            .closedReason(ClosedReason.MEET_TIME_PASSED)
            .closedAt(closedAtUtc)
            .insert(jdbcTemplate);

    return openRoom(postId);
  }

  private long openRoom(long postId) {
    ChatRoom room = ChatRoom.openFor(postId, hostId);
    room.invite(memberId);

    return chatRoomRepository.saveAndFlush(room).getId();
  }

  private void insertMessage(long roomId, MessageStatus status) {
    jdbcTemplate.update(
        """
        INSERT INTO chat_message (room_id, sender_id, client_message_id, content, status,
                                  created_at, updated_at)
        VALUES (?, ?, ?, '보관 기간이 지난 대화', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
        """,
        roomId,
        memberId,
        UUID.randomUUID().toString(),
        status.name());
  }

  /** 방에 실린 사진 하나를 넣고 객체 키를 준다. 저장소 목을 키별로 갈라 세울 때 쓴다. */
  private String insertImageKey(long roomId) {
    String objectKey = "chat/" + roomId + "/" + UUID.randomUUID() + ".jpg";
    insertImage(roomId, ChatImageStatus.ATTACHED, objectKey);

    return objectKey;
  }

  private long insertImage(long roomId, ChatImageStatus status) {
    return insertImage(roomId, status, "chat/" + roomId + "/" + UUID.randomUUID() + ".jpg");
  }

  private long insertImage(long roomId, ChatImageStatus status, String objectKey) {
    jdbcTemplate.update(
        """
        INSERT INTO chat_image (room_id, uploader_id, object_key, content_type, byte_size, status,
                                created_at, updated_at)
        VALUES (?, ?, ?, 'image/jpeg', 204800, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
        """,
        roomId,
        memberId,
        objectKey,
        status.name());

    return jdbcTemplate.queryForObject(
        "SELECT id FROM chat_image WHERE object_key = ?", Long.class, objectKey);
  }

  private int messageCountOf(long roomId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM chat_message WHERE room_id = ?", Integer.class, roomId);
  }

  private int imageCountOf(long roomId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM chat_image WHERE room_id = ?", Integer.class, roomId);
  }

  /**
   * 파기 표시는 엔티티로 읽는다.
   *
   * <p>못박는 쪽이 더티 체킹이라 (다른 자리와 달리 벌크 UPDATE 가 아니다) 이 검사의 트랜잭션 안에서는 아직 DB 에 안 나가 있다 — JDBC 로 읽으면
   * <b>배치가 표시했는데도 null 이 나온다.</b> 지운 행들을 JDBC 로 세는 것은 그쪽이 벌크 삭제라 이미 나가 있어서다.
   */
  private LocalDateTime purgedAtOf(long roomId) {
    return chatRoomRepository.findById(roomId).orElseThrow().getPurgedAt();
  }

  private String statusOf(long imageId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM chat_image WHERE id = ?", String.class, imageId);
  }

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
