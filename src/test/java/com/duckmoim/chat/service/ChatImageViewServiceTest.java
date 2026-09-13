package com.duckmoim.chat.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.duckmoim.chat.domain.ChatImageStorage;
import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.ExifStatus;
import com.duckmoim.chat.domain.Message;
import com.duckmoim.chat.domain.UploadedChatImage;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 이미지 접근 제어의 검증 기준 (CH-15) — <b>비멤버 요청에 서명이 발급되지 않는다.</b>
 *
 * <p>나머지 하나인 「서명 만료 후 원본 주소로 접근 불가」는 코드로 증명할 수 없다. 버킷이 비공개라는 전제와 서명의 수명이 그것을 성립시키고, 수명이 실제로 실리는지는
 * {@code S3ChatImageStorageTest} 가 본다.
 *
 * <p><b>「비멤버」가 셋이다</b> — 남 · 나간 사람 · 다른 방 멤버. 셋 다 같은 답이어야 하고, 셋째는 방 판정만으로는 걸리지 않아 메시지 쪽 대조가 필요하다.
 *
 * <p><b>저장소를 목으로 세운다</b> ({@code ChatImageServiceTest} 와 같은 구성이라 컨텍스트를 함께 쓴다). 실물 S3 를 쓰면 이 검사가
 * 자격증명과 네트워크에 달린다.
 */
@SpringBootTest
@DisplayName("채팅 이미지 열람")
class ChatImageViewServiceTest {

  private static final LocalDateTime MEET_AT_UTC = LocalDateTime.of(2026, 10, 1, 9, 0);
  private static final String JPEG = "image/jpeg";
  private static final long SMALL = 204_800L;
  private static final String VIEW_URL = "https://s3.example/get";

  @Autowired private ChatImageViewService chatImageViewService;
  @Autowired private ChatImageService chatImageService;
  @Autowired private ChatMessageSendService chatMessageSendService;
  @Autowired private ChatMessageDeleteService chatMessageDeleteService;
  @Autowired private ChatRoomLeaveService chatRoomLeaveService;
  @Autowired private ChatMessageRepository chatMessageRepository;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private ChatImageStorage storage;

  private long hostId;
  private long memberId;
  private long leaverId;
  private long strangerId;
  private long roomId;

  @BeforeEach
  void setUp() {
    hostId = aUser().nickname("방장" + suffix()).insert(jdbcTemplate);
    memberId = aUser().nickname("멤버" + suffix()).insert(jdbcTemplate);
    leaverId = aUser().nickname("나간이" + suffix()).insert(jdbcTemplate);
    strangerId = aUser().nickname("남" + suffix()).insert(jdbcTemplate);

    long postId = aCompanionPost().hostId(hostId).meetAt(MEET_AT_UTC).insert(jdbcTemplate);

    ChatRoom room = ChatRoom.openFor(postId, hostId);
    room.invite(memberId);
    room.invite(leaverId);
    roomId = chatRoomRepository.saveAndFlush(room).getId();

    given(storage.presignView(anyString(), any())).willReturn(VIEW_URL);
  }

  /** 주소를 저장해 두지 않기 때문에 이 경로가 사진을 볼 수 있는 유일한 문이다 (계획서 8.2). */
  @DisplayName("방 멤버는 사진을 볼 수 있는 서명된 주소를 받는다.")
  @Test
  void viewUrlsOf_returnsSignedUrlForMember() {
    Long messageId = messageWithImage();

    List<ChatImageView> issued =
        chatImageViewService.viewUrlsOf(roomId, List.of(messageId), memberId);

    assertThat(issued).hasSize(1);
    assertThat(issued.get(0).messageId()).isEqualTo(messageId);
    assertThat(issued.get(0).signed().url()).isEqualTo(VIEW_URL);
    assertThat(issued.get(0).signed().remaining()).isPositive();
  }

  /**
   * <b>사진 수와 무관하게 요청 하나다</b> (PR #152 리뷰 ③).
   *
   * <p>한 장에 요청 하나였을 때 20장짜리 방을 여는 것이 20요청 · 60쿼리였다.
   *
   * <p><b>물어본 순서를 지킨다.</b> 저장소가 돌려주는 순서는 질의 계획에 달렸고, 같은 입력에 같은 출력인 편이 디버깅에서 싸다.
   */
  @DisplayName("여러 장을 한 번에 발급하고 물어본 순서를 지킨다.")
  @Test
  void viewUrlsOf_issuesManyInAskedOrder() {
    Long first = messageWithImage();
    Long second = messageWithImage();

    List<ChatImageView> issued =
        chatImageViewService.viewUrlsOf(roomId, List.of(second, first), memberId);

    assertThat(issued).extracting(ChatImageView::messageId).containsExactly(second, first);
  }

  /** 같은 번호를 두 번 물어도 한 건이다. 화면이 messageId 로 맞추므로 중복은 붙일 자리가 없다. */
  @DisplayName("같은 메시지 번호를 두 번 물어도 한 건만 나온다.")
  @Test
  void viewUrlsOf_dedupesAskedIds() {
    Long messageId = messageWithImage();

    List<ChatImageView> issued =
        chatImageViewService.viewUrlsOf(roomId, List.of(messageId, messageId), memberId);

    assertThat(issued).hasSize(1);
  }

  /** 올린 사람만 볼 수 있다면 사진을 보낼 이유가 없다 — 대화에 실린 사진은 그 방 사람들이 보라고 올린 것이다. */
  @DisplayName("보낸 사람이 아닌 방 멤버도 사진을 볼 수 있다.")
  @Test
  void viewUrlsOf_allowsOtherMembers() {
    Long messageId = messageWithImage();

    List<ChatImageView> issued =
        chatImageViewService.viewUrlsOf(roomId, List.of(messageId), hostId);

    assertThat(issued).singleElement().extracting(view -> view.signed().url()).isEqualTo(VIEW_URL);
  }

  /**
   * <b>검증 기준.</b> 비멤버 요청에 서명이 발급되지 않는다 (I-18).
   *
   * <p>발급하지 않는 것이 요점이다 — 응답에서 필드를 빼는 방식은 저장소에 있는 파일을 막지 못한다 (도메인 「가시성과 권한」).
   */
  @DisplayName("방 멤버가 아니면 서명이 발급되지 않는다.")
  @Test
  void viewUrlsOf_rejectsNonMember() {
    Long messageId = messageWithImage();

    assertThatThrownBy(
            () -> chatImageViewService.viewUrlsOf(roomId, List.of(messageId), strangerId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);

    verify(storage, times(0)).presignView(anyString(), any());
  }

  /** <b>검증 기준의 둘째 얼굴이다</b> (CH-18). 나가면 그 순간 멤버가 아니고, 이미 받아 둔 주소는 수명이 다하면 끊긴다. */
  @DisplayName("방을 나간 사람에게는 서명이 발급되지 않는다.")
  @Test
  void viewUrlsOf_rejectsLeftMember() {
    Long messageId = messageWithImage();
    chatRoomLeaveService.leave(roomId, leaverId);

    assertThatThrownBy(() -> chatImageViewService.viewUrlsOf(roomId, List.of(messageId), leaverId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
  }

  @DisplayName("없는 방으로 물으면 404 다.")
  @Test
  void viewUrlsOf_rejectsMissingRoom() {
    Long messageId = messageWithImage();

    assertThatThrownBy(
            () -> chatImageViewService.viewUrlsOf(404_404L, List.of(messageId), memberId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_FOUND);
  }

  /**
   * <b>목록에서만 사라지고 사진은 계속 보이는 상태를 막는다</b> (CH-12).
   *
   * <p>{@code imageId} 를 이미 받아 둔 클라이언트는 삭제 뒤에도 그 번호를 쥐고 있다 — 이 경로가 메시지 상태를 안 보면 지운 사진이 그대로 나간다.
   *
   * <p><b>함께 물은 멀쩡한 사진은 그대로 나간다.</b> 목록을 그리는 중에 남이 자기 사진을 지우는 것은 정상적인 일이고, 그때 요청 전체를 거절하면 화면이 사진 없이
   * 뜬다.
   */
  @DisplayName("지운 메시지의 사진은 응답에서 빠지고 나머지는 나온다.")
  @Test
  void viewUrlsOf_dropsDeletedMessage() {
    Long deleted = messageWithImage();
    Long alive = messageWithImage();
    chatMessageDeleteService.delete(roomId, deleted, memberId);

    List<ChatImageView> issued =
        chatImageViewService.viewUrlsOf(roomId, List.of(deleted, alive), memberId);

    assertThat(issued).extracting(ChatImageView::messageId).containsExactly(alive);
  }

  /** 블라인드는 운영이 가린 것이라 더더욱 보이면 안 된다 (AD-09). 삭제와 같은 판정 하나로 막힌다. */
  @DisplayName("블라인드된 메시지의 사진은 응답에서 빠진다.")
  @Test
  void viewUrlsOf_dropsBlindedMessage() {
    Long messageId = messageWithImage();
    blind(messageId);

    List<ChatImageView> issued =
        chatImageViewService.viewUrlsOf(roomId, List.of(messageId), memberId);

    assertThat(issued).isEmpty();
    verify(storage, times(0)).presignView(anyString(), any());
  }

  /**
   * <b>내가 멤버인 방의 번호를 붙여 남의 방 사진을 보는 것을 막는다.</b>
   *
   * <p>요청이 방 번호와 메시지 번호를 따로 주므로 대조하지 않으면 방 멤버 판정이 아무 일도 하지 않게 된다 — {@code ChatMessageDeleteService}
   * 가 같은 이유로 같은 대조를 한다.
   */
  @DisplayName("다른 방의 메시지 번호는 응답에서 빠진다.")
  @Test
  void viewUrlsOf_dropsMessageFromAnotherRoom() {
    Long messageId = messageWithImage();
    long otherRoomId = otherRoomOf(strangerId);

    List<ChatImageView> issued =
        chatImageViewService.viewUrlsOf(otherRoomId, List.of(messageId), strangerId);

    assertThat(issued).isEmpty();
  }

  /** 사진 없는 메시지와 없는 메시지가 같은 답이다 — 갈라서 답하면 그 번호가 존재한다는 사실이 새어 나간다. */
  @DisplayName("사진이 없는 메시지와 없는 메시지가 똑같이 빠진다.")
  @Test
  void viewUrlsOf_dropsMessageWithoutImage() {
    Long noImage =
        chatMessageSendService.send(roomId, memberId, newClientId(), "사진 없음", null).messageId();

    List<ChatImageView> issued =
        chatImageViewService.viewUrlsOf(roomId, List.of(noImage, 404_404L), memberId);

    assertThat(issued).isEmpty();
  }

  /**
   * <b>벗기기 전에는 보여주지 않는다</b> (CH-16 · STAR-116 과의 계약).
   *
   * <p>사진이 실제로 밖으로 나가는 자리가 이 경로뿐이라, 벗기는 워커가 있어도 이 판정이 없으면 <b>원본이 먼저 나간다.</b> 확정 직후의 사진은 아직 {@code
   * PENDING} 이고 그 상태로 전송까지 갈 수 있다 — 전송은 EXIF 축을 보지 않기 때문이다.
   */
  @DisplayName("EXIF 를 아직 안 벗긴 사진은 서명이 발급되지 않는다.")
  @Test
  void viewUrlsOf_dropsImageWithPendingExif() {
    Long messageId = messageWithUnstrippedImage();

    List<ChatImageView> issued =
        chatImageViewService.viewUrlsOf(roomId, List.of(messageId), memberId);

    assertThat(issued).isEmpty();
    verify(storage, times(0)).presignView(anyString(), any());
  }

  /** 벗기지 못한 사진은 <b>영구히</b> 안 보여준다 — 원본에 무엇이 남았는지 모른다. */
  @DisplayName("EXIF 제거에 실패한 사진은 영구히 서명이 발급되지 않는다.")
  @Test
  void viewUrlsOf_dropsImageWithFailedExif() {
    Long messageId = messageWithImage();
    markExif(messageId, ExifStatus.FAILED);

    List<ChatImageView> issued =
        chatImageViewService.viewUrlsOf(roomId, List.of(messageId), memberId);

    assertThat(issued).isEmpty();
  }

  /**
   * <b>캐시가 이 경로에 실제로 붙어 있는지 본다.</b>
   *
   * <p>같은 사진을 둘이 열어도 서명은 한 번이다 — 서명이 사람이 아니라 객체에 걸리기 때문이고, 그래서 <b>둘이 같은 주소를 받아 브라우저 캐시가 맞는다.</b>
   * 재사용 창의 경계 자체는 {@code ChatImageViewUrlCacheTest} 가 본다.
   */
  @DisplayName("같은 사진을 여럿이 열어도 서명은 한 번만 만든다.")
  @Test
  void viewUrlsOf_signsOncePerObject() {
    AtomicInteger signCount = new AtomicInteger();
    given(storage.presignView(anyString(), any()))
        .willAnswer(call -> VIEW_URL + "?sig=" + signCount.incrementAndGet());
    Long messageId = messageWithImage();

    String first =
        chatImageViewService.viewUrlsOf(roomId, List.of(messageId), memberId).get(0).signed().url();
    String second =
        chatImageViewService.viewUrlsOf(roomId, List.of(messageId), hostId).get(0).signed().url();

    assertThat(second).isEqualTo(first);
    assertThat(signCount.get()).isEqualTo(1);
  }

  /**
   * 업로드 → 확정 → 전송까지 지나고 <b>EXIF 까지 벗긴</b> 사진 (CH-14 · CH-16).
   *
   * <p>벗기는 것까지 여기서 하는 이유는, 그것이 이 경로가 사진을 <b>내보내는</b> 정상 상태이기 때문이다 — 안 벗긴 상태는 그 자체가 검사 대상이라 {@link
   * #viewUrlsOf_dropsImageWithPendingExif} 가 따로 본다.
   */
  private Long messageWithImage() {
    Long messageId = messageWithUnstrippedImage();
    markExif(messageId, ExifStatus.STRIPPED);

    return messageId;
  }

  /** 확정 직후의 사진은 아직 {@code PENDING} 이다 — 전송은 EXIF 축을 보지 않아 그대로 메시지에 실린다. */
  private Long messageWithUnstrippedImage() {
    given(storage.presignUpload(anyString(), anyString(), anyLong()))
        .willReturn("https://s3.example/put");
    Long imageId = chatImageService.issueUpload(roomId, memberId, JPEG, SMALL).imageId();

    given(storage.findUploaded(anyString()))
        .willReturn(Optional.of(new UploadedChatImage(JPEG, SMALL)));
    chatImageService.confirm(roomId, memberId, imageId);

    return chatMessageSendService
        .send(roomId, memberId, newClientId(), "사진 보냄", imageId)
        .messageId();
  }

  /**
   * EXIF 축을 원하는 상태로 옮긴다 (CH-16).
   *
   * <p>워커를 돌리지 않고 열로 직접 쓴다 — 여기서 보는 것은 벗기는 절차가 아니라 <b>그 결과에 따라 서명이 나가는가</b>이고, 절차 자체는 {@code
   * ChatImageExifWorkerTest} 가 본다.
   */
  private void markExif(Long messageId, ExifStatus status) {
    jdbcTemplate.update(
        "UPDATE chat_image SET exif_status = ?"
            + " WHERE id = (SELECT image_id FROM chat_message WHERE id = ?)",
        status.name(),
        messageId);
  }

  /** 관리자 경로를 거치지 않고 상태만 만든다. 여기서 보는 것은 블라인드 절차가 아니라 <b>가려진 뒤</b>다. */
  private void blind(Long messageId) {
    Message message = chatMessageRepository.findById(messageId).orElseThrow();
    message.blind();
    chatMessageRepository.saveAndFlush(message);
  }

  /** 남이 멤버인 다른 방. 방 판정만으로는 안 걸리고 메시지 쪽 대조가 있어야 막히는 경로다. */
  private long otherRoomOf(long ownerId) {
    long postId = aCompanionPost().hostId(ownerId).meetAt(MEET_AT_UTC).insert(jdbcTemplate);

    return chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, ownerId)).getId();
  }

  private String newClientId() {
    return UUID.randomUUID().toString();
  }

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
