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
import com.duckmoim.chat.domain.Message;
import com.duckmoim.chat.domain.SignedChatImageUrl;
import com.duckmoim.chat.domain.UploadedChatImage;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import java.time.LocalDateTime;
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
  void viewUrlOf_returnsSignedUrlForMember() {
    Long messageId = messageWithImage();

    SignedChatImageUrl signed = chatImageViewService.viewUrlOf(roomId, messageId, memberId);

    assertThat(signed.url()).isEqualTo(VIEW_URL);
    assertThat(signed.remaining()).isPositive();
  }

  /** 올린 사람만 볼 수 있다면 사진을 보낼 이유가 없다 — 대화에 실린 사진은 그 방 사람들이 보라고 올린 것이다. */
  @DisplayName("보낸 사람이 아닌 방 멤버도 사진을 볼 수 있다.")
  @Test
  void viewUrlOf_allowsOtherMembers() {
    Long messageId = messageWithImage();

    SignedChatImageUrl signed = chatImageViewService.viewUrlOf(roomId, messageId, hostId);

    assertThat(signed.url()).isEqualTo(VIEW_URL);
  }

  /**
   * <b>검증 기준.</b> 비멤버 요청에 서명이 발급되지 않는다 (I-18).
   *
   * <p>발급하지 않는 것이 요점이다 — 응답에서 필드를 빼는 방식은 저장소에 있는 파일을 막지 못한다 (도메인 「가시성과 권한」).
   */
  @DisplayName("방 멤버가 아니면 서명이 발급되지 않는다.")
  @Test
  void viewUrlOf_rejectsNonMember() {
    Long messageId = messageWithImage();

    assertThatThrownBy(() -> chatImageViewService.viewUrlOf(roomId, messageId, strangerId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);

    verify(storage, times(0)).presignView(anyString(), any());
  }

  /** <b>검증 기준의 둘째 얼굴이다</b> (CH-18). 나가면 그 순간 멤버가 아니고, 이미 받아 둔 주소는 수명이 다하면 끊긴다. */
  @DisplayName("방을 나간 사람에게는 서명이 발급되지 않는다.")
  @Test
  void viewUrlOf_rejectsLeftMember() {
    Long messageId = messageWithImage();
    chatRoomLeaveService.leave(roomId, leaverId);

    assertThatThrownBy(() -> chatImageViewService.viewUrlOf(roomId, messageId, leaverId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
  }

  @DisplayName("없는 방으로 물으면 404 다.")
  @Test
  void viewUrlOf_rejectsMissingRoom() {
    Long messageId = messageWithImage();

    assertThatThrownBy(() -> chatImageViewService.viewUrlOf(404_404L, messageId, memberId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_FOUND);
  }

  /**
   * <b>목록에서만 사라지고 사진은 계속 보이는 상태를 막는다</b> (CH-12).
   *
   * <p>{@code imageId} 를 이미 받아 둔 클라이언트는 삭제 뒤에도 그 번호를 쥐고 있다 — 이 경로가 메시지 상태를 안 보면 지운 사진이 그대로 나간다.
   */
  @DisplayName("지운 메시지의 사진에는 서명이 발급되지 않는다.")
  @Test
  void viewUrlOf_rejectsDeletedMessage() {
    Long messageId = messageWithImage();
    chatMessageDeleteService.delete(roomId, messageId, memberId);

    assertThatThrownBy(() -> chatImageViewService.viewUrlOf(roomId, messageId, memberId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND);
  }

  /** 블라인드는 운영이 가린 것이라 더더욱 보이면 안 된다 (AD-09). 삭제와 같은 판정 하나로 막힌다. */
  @DisplayName("블라인드된 메시지의 사진에는 서명이 발급되지 않는다.")
  @Test
  void viewUrlOf_rejectsBlindedMessage() {
    Long messageId = messageWithImage();
    blind(messageId);

    assertThatThrownBy(() -> chatImageViewService.viewUrlOf(roomId, messageId, memberId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND);
  }

  /**
   * <b>내가 멤버인 방의 번호를 붙여 남의 방 사진을 보는 것을 막는다.</b>
   *
   * <p>경로가 방 번호와 메시지 번호를 따로 주므로 대조하지 않으면 방 멤버 판정이 아무 일도 하지 않게 된다 — {@code ChatMessageDeleteService}
   * 가 같은 이유로 같은 대조를 한다.
   */
  @DisplayName("다른 방의 메시지 번호로 물으면 404 다.")
  @Test
  void viewUrlOf_rejectsMessageFromAnotherRoom() {
    Long messageId = messageWithImage();
    long otherRoomId = otherRoomOf(strangerId);

    assertThatThrownBy(() -> chatImageViewService.viewUrlOf(otherRoomId, messageId, strangerId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND);
  }

  /** 사진 없는 메시지와 없는 메시지가 같은 답이다 — 갈라서 답하면 그 번호가 존재한다는 사실이 새어 나간다. */
  @DisplayName("사진이 없는 메시지로 물으면 404 다.")
  @Test
  void viewUrlOf_rejectsMessageWithoutImage() {
    Long messageId =
        chatMessageSendService.send(roomId, memberId, newClientId(), "사진 없음", null).messageId();

    assertThatThrownBy(() -> chatImageViewService.viewUrlOf(roomId, messageId, memberId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND);
  }

  @DisplayName("없는 메시지 번호로 물으면 404 다.")
  @Test
  void viewUrlOf_rejectsMissingMessage() {
    assertThatThrownBy(() -> chatImageViewService.viewUrlOf(roomId, 404_404L, memberId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND);
  }

  /**
   * <b>캐시가 이 경로에 실제로 붙어 있는지 본다.</b>
   *
   * <p>같은 사진을 둘이 열어도 서명은 한 번이다 — 서명이 사람이 아니라 객체에 걸리기 때문이고, 그래서 <b>둘이 같은 주소를 받아 브라우저 캐시가 맞는다.</b>
   * 재사용 창의 경계 자체는 {@code ChatImageViewUrlCacheTest} 가 본다.
   */
  @DisplayName("같은 사진을 여럿이 열어도 서명은 한 번만 만든다.")
  @Test
  void viewUrlOf_signsOncePerObject() {
    AtomicInteger signCount = new AtomicInteger();
    given(storage.presignView(anyString(), any()))
        .willAnswer(call -> VIEW_URL + "?sig=" + signCount.incrementAndGet());
    Long messageId = messageWithImage();

    String first = chatImageViewService.viewUrlOf(roomId, messageId, memberId).url();
    String second = chatImageViewService.viewUrlOf(roomId, messageId, hostId).url();

    assertThat(second).isEqualTo(first);
    assertThat(signCount.get()).isEqualTo(1);
  }

  /** 업로드 → 확정 → 전송까지 지나야 사진이 메시지에 실린다 (CH-14). 그 상태가 이 경로의 입력이다. */
  private Long messageWithImage() {
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
