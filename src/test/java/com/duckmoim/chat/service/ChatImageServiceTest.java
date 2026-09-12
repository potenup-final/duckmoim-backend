package com.duckmoim.chat.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.duckmoim.chat.domain.ChatImage;
import com.duckmoim.chat.domain.ChatImageStatus;
import com.duckmoim.chat.domain.ChatImageStorage;
import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.UploadedChatImage;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatImageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 이미지 업로드의 검증 기준 절반 (CH-14) — <b>허용 밖 형식·크기 400.</b>
 *
 * <p>나머지 절반인 「업로드 확인 전 메시지 전송 시 400」은 전송 쪽에서 본다 ({@code ChatMessageSendServiceTest}).
 *
 * <p><b>저장소를 목으로 세운다.</b> 실물 S3 를 쓰면 이 검사가 자격증명과 네트워크에 달리고, 대역({@code StubChatImageStorage})을 쓰면
 * 확정이 항상 실패해 「확인 성공」 경로를 못 본다 — 그쪽 자바독이 <i>"로컬에서 끝까지 해보려면 버킷이 있어야 한다"</i> 고 적은 자리다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 발급이 행을 만들고 확정이 그 행을 바꾸는데, 하나로 묶으면 <b>그 둘이 같은 영속성
 * 컨텍스트를 공유해</b> 실제 경로와 달라진다 — 각 검사가 자기가 만든 행만 본다.
 */
@SpringBootTest
@DisplayName("채팅 이미지 업로드")
class ChatImageServiceTest {

  private static final LocalDateTime MEET_AT_UTC = LocalDateTime.of(2026, 10, 1, 9, 0);
  private static final String JPEG = "image/jpeg";
  private static final long SMALL = 204_800L;

  @Autowired private ChatImageService chatImageService;
  @Autowired private ChatImageRepository chatImageRepository;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private ChatImageStorage storage;

  private long hostId;
  private long memberId;
  private long strangerId;
  private long roomId;

  @BeforeEach
  void setUp() {
    hostId = aUser().nickname("방장" + suffix()).insert(jdbcTemplate);
    memberId = aUser().nickname("멤버" + suffix()).insert(jdbcTemplate);
    strangerId = aUser().nickname("남" + suffix()).insert(jdbcTemplate);

    long postId = aCompanionPost().hostId(hostId).meetAt(MEET_AT_UTC).insert(jdbcTemplate);

    ChatRoom room = ChatRoom.openFor(postId, hostId);
    room.invite(memberId);
    roomId = chatRoomRepository.saveAndFlush(room).getId();
  }

  /** 브라우저가 올릴 주소를 받는 것이 이 경로의 전부다. 바이트는 서버를 지나지 않는다. */
  @DisplayName("방 멤버는 서명된 업로드 주소를 받는다.")
  @Test
  void issueUpload_returnsPresignedUrl() {
    given(storage.presignUpload(anyString(), anyString())).willReturn("https://s3.example/put");

    ChatImageUpload upload = chatImageService.issueUpload(roomId, memberId, JPEG, SMALL);

    assertThat(upload.imageId()).isNotNull();
    assertThat(upload.uploadUrl()).isEqualTo("https://s3.example/put");
    assertThat(upload.expiresInSeconds()).isPositive();
  }

  /**
   * <b>올라오기 전에 행이 생긴다.</b> 이것이 CH-17 의 전제다 — 올리다 만 객체도 행이 있어야 배치가 찾아 지운다.
   *
   * <p>{@code AU-08} 은 발급 때 DB 를 안 건드린다. 그쪽은 지울 필요가 없어 추적할 이유가 없었다.
   */
  @DisplayName("발급 시점에 PENDING 행이 생긴다.")
  @Test
  void issueUpload_createsPendingRow() {
    given(storage.presignUpload(anyString(), anyString())).willReturn("https://s3.example/put");

    ChatImageUpload upload = chatImageService.issueUpload(roomId, memberId, JPEG, SMALL);

    ChatImage saved = chatImageRepository.findById(upload.imageId()).orElseThrow();
    assertThat(saved.getStatus()).isEqualTo(ChatImageStatus.PENDING);
    assertThat(saved.getObjectKey()).startsWith("chat/" + roomId + "/");
    assertThat(saved.getByteSize()).isZero();
  }

  /** 공개 주소를 만들지 않기로 한 결정이 키 모양으로 남는다 (계획서 8.2). 접두어가 버킷 정책의 경계와 같은 값이다. */
  @DisplayName("객체 키가 chat/ 접두어 아래에 만들어진다.")
  @Test
  void issueUpload_keepsKeyUnderChatPrefix() {
    given(storage.presignUpload(anyString(), anyString())).willReturn("https://s3.example/put");

    ChatImageUpload upload = chatImageService.issueUpload(roomId, memberId, JPEG, SMALL);

    assertThat(chatImageRepository.findById(upload.imageId()).orElseThrow().getObjectKey())
        .startsWith("chat/")
        .endsWith(".jpg");
  }

  /** <b>검증 기준.</b> 올리기 전에 막는 것이 올린 뒤 버리는 것보다 싸다. */
  @DisplayName("허용 밖 형식이면 서명을 발급하지 않는다.")
  @Test
  void issueUpload_rejectsDisallowedType() {
    assertThatThrownBy(() -> chatImageService.issueUpload(roomId, memberId, "image/gif", SMALL))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_IMAGE_TYPE_NOT_ALLOWED);
  }

  /** <b>검증 기준.</b> 크기와 형식을 갈라서 답한다 — 사용자가 고쳐야 하는 것이 다르다. */
  @DisplayName("상한을 넘는 크기면 서명을 발급하지 않는다.")
  @Test
  void issueUpload_rejectsTooLarge() {
    assertThatThrownBy(() -> chatImageService.issueUpload(roomId, memberId, JPEG, 99_999_999L))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_IMAGE_TOO_LARGE);
  }

  /** 판정을 먼저 하고 행을 만든다 — 순서가 뒤집히면 틀린 요청마다 지울 것 없는 행이 쌓인다. */
  @DisplayName("형식이 틀린 요청은 행을 남기지 않는다.")
  @Test
  void issueUpload_leavesNoRowWhenRejected() {
    long before = chatImageRepository.count();

    assertThatThrownBy(() -> chatImageService.issueUpload(roomId, memberId, "image/gif", SMALL))
        .isInstanceOf(BusinessException.class);

    assertThat(chatImageRepository.count()).isEqualTo(before);
  }

  /** 방은 여럿이 공유하는 자원이라 「내 것」으로는 부족하다 — I-18 이 걸린 자리다. */
  @DisplayName("방 멤버가 아니면 서명을 발급받지 못한다.")
  @Test
  void issueUpload_rejectsNonMember() {
    assertThatThrownBy(() -> chatImageService.issueUpload(roomId, strangerId, JPEG, SMALL))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
  }

  @DisplayName("없는 방에는 서명을 발급하지 않는다.")
  @Test
  void issueUpload_rejectsMissingRoom() {
    assertThatThrownBy(() -> chatImageService.issueUpload(404_404L, memberId, JPEG, SMALL))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_FOUND);
  }

  /** 실제 값으로 덮는다 — 선언은 거짓말일 수 있고, 판정은 확정 시점에 끝난다. */
  @DisplayName("확정하면 실제 형식과 크기로 CONFIRMED 가 된다.")
  @Test
  void confirm_recordsActualMetadata() {
    Long imageId = issued();
    given(storage.findUploaded(anyString()))
        .willReturn(Optional.of(new UploadedChatImage(JPEG, 345_678L)));

    chatImageService.confirm(roomId, memberId, imageId);

    ChatImage confirmed = chatImageRepository.findById(imageId).orElseThrow();
    assertThat(confirmed.getStatus()).isEqualTo(ChatImageStatus.CONFIRMED);
    assertThat(confirmed.getByteSize()).isEqualTo(345_678L);
  }

  /** 확정 응답을 못 받은 클라이언트가 다시 부르는 것이 정상 경로다. */
  @DisplayName("확정을 두 번 불러도 같은 결과다.")
  @Test
  void confirm_isIdempotent() {
    Long imageId = issued();
    given(storage.findUploaded(anyString()))
        .willReturn(Optional.of(new UploadedChatImage(JPEG, SMALL)));

    chatImageService.confirm(roomId, memberId, imageId);

    assertThatCode(() -> chatImageService.confirm(roomId, memberId, imageId))
        .doesNotThrowAnyException();
    assertThat(chatImageRepository.findById(imageId).orElseThrow().getStatus())
        .isEqualTo(ChatImageStatus.CONFIRMED);
  }

  /** 사용자가 파일 선택을 취소한 뒤 확정을 부르면 이 상태가 된다 — 예외가 아니라 정상 흐름이다. */
  @DisplayName("저장소에 올라온 것이 없으면 확정이 400 이다.")
  @Test
  void confirm_rejectsWhenNothingUploaded() {
    Long imageId = issued();
    given(storage.findUploaded(anyString())).willReturn(Optional.empty());

    assertThatThrownBy(() -> chatImageService.confirm(roomId, memberId, imageId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_IMAGE_NOT_UPLOADED);
  }

  /** <b>선언은 거짓말일 수 있다.</b> 5MB 라 하고 50MB 를 올리면 발급 검사는 통과한다. */
  @DisplayName("실제 크기가 상한을 넘으면 확정이 400 이다.")
  @Test
  void confirm_rejectsActualTooLarge() {
    Long imageId = issued();
    given(storage.findUploaded(anyString()))
        .willReturn(Optional.of(new UploadedChatImage(JPEG, 99_999_999L)));

    assertThatThrownBy(() -> chatImageService.confirm(roomId, memberId, imageId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_IMAGE_TOO_LARGE);
  }

  /**
   * <b>「남의 것이다」로 답하지 않는다.</b> 그러면 그 번호의 사진이 존재한다는 사실을 알려준다 — AU-08 이 접두어 검사와 존재 검사에 같은 코드를 쓴 것과 같은
   * 판단이다.
   */
  @DisplayName("남이 올린 이미지는 확정할 수 없고 없는 것과 같은 답이다.")
  @Test
  void confirm_hidesOtherUploads() {
    Long imageId = issued();

    assertThatThrownBy(() -> chatImageService.confirm(roomId, hostId, imageId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_IMAGE_NOT_UPLOADED);
  }

  @DisplayName("없는 이미지 번호로 확정하면 400 이다.")
  @Test
  void confirm_rejectsMissingImage() {
    assertThatThrownBy(() -> chatImageService.confirm(roomId, memberId, 404_404L))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_IMAGE_NOT_UPLOADED);
  }

  private Long issued() {
    given(storage.presignUpload(anyString(), anyString())).willReturn("https://s3.example/put");
    return chatImageService.issueUpload(roomId, memberId, JPEG, SMALL).imageId();
  }

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
