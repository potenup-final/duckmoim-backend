package com.duckmoim.chat.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.Message;
import com.duckmoim.chat.domain.MessageListQuery;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.safety.domain.ReportReason;
import com.duckmoim.safety.domain.ReportTargetType;
import com.duckmoim.safety.service.ReportCommand;
import com.duckmoim.safety.service.ReportCommandService;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 나간 사람에게 닫히는 문 전부 (CH-18).
 *
 * <p><b>한 곳에 모은 것이 이 클래스의 목적이다.</b> 판정은 {@link ChatRoomMembershipReader} 한 군데에 있지만 그것을 부르는 <b>입구는
 * 여럿</b>이고, 새 입구를 내면서 한 줄을 빠뜨리면 그 입구만 열린 채로 초록불이 된다. 여기서 입구를 열거해 두면 다음 입구를 더할 때 <b>이 목록에 줄을 더하는 것이
 * 자연스러운 자리</b>가 된다.
 *
 * <p><b>여기 없는 문 둘.</b> 스트림 끊김은 {@code ChatStreamServiceTest} 에 있다 — 실물 Redis 가 필요하고, 그쪽이 보는 것은 「이미
 * 열려 있는 연결을 끊는다」라 입구 판정과 다른 문제다. 퇴장이 남긴 결과로서의 목록·상세는 {@code ChatRoomLeaveServiceTest} 에도 있다 — 그쪽은
 * <b>퇴장이 실제로 그 결과를 낸다</b>를, 여기는 <b>나간 상태에서 문이 닫혀 있다</b>를 본다.
 *
 * <p><b>마지막 하나는 반대로 열려 있어야 한다.</b> 신고 접수는 방 멤버인지 보지 않는다 (2026-09-12 · CH-21) — 막으면 <b>괴롭히고 나가기</b>
 * 길이 생기고, 괴롭힘을 당해 나간 사람이 정확히 그 모양이다. 닫는 것만 테스트하면 다음 사람이 「나간 사람은 전부 막는다」로 읽고 접수까지 닫는다.
 */
@SpringBootTest
@Transactional
class ChatLeftMemberAccessTest {

  @Autowired private ChatRoomListQueryService chatRoomListQueryService;
  @Autowired private ChatRoomDetailQueryService chatRoomDetailQueryService;
  @Autowired private ChatMessageQueryService chatMessageQueryService;
  @Autowired private ChatMessageSendService chatMessageSendService;
  @Autowired private ChatRoomReadService chatRoomReadService;
  @Autowired private ChatImageService chatImageService;
  @Autowired private ReportCommandService reportCommandService;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private ChatMessageRepository chatMessageRepository;
  @Autowired private JdbcTemplate jdbc;

  private long hostId;
  private long leftId;
  private long roomId;
  private long messageId;

  @BeforeEach
  void setUp() {
    hostId = aUser().nickname("방장" + suffix()).insert(jdbc);
    leftId = aUser().nickname("나간사람" + suffix()).insert(jdbc);

    long postId = aCompanionPost().hostId(hostId).title("CH-18 픽스처 " + suffix()).insert(jdbc);
    ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, hostId));
    room.invite(leftId);
    roomId = chatRoomRepository.saveAndFlush(room).getId();

    // 나가기 전에 오간 말. 아래에서 「대화는 막히고 신고는 열려 있다」의 대상이 된다
    messageId =
        chatMessageRepository
            .saveAndFlush(Message.send(roomId, hostId, UUID.randomUUID().toString(), "험한 말", null))
            .getId();

    room.leave(leftId, hostId);
    chatRoomRepository.saveAndFlush(room);
  }

  @DisplayName("나간 사람의 방 목록에 그 방이 없다.")
  @Test
  void roomList() {
    assertThat(chatRoomListQueryService.findRooms(leftId))
        .extracting(ChatRoomSummaryView::roomId)
        .doesNotContain(roomId);
  }

  @DisplayName("나간 사람은 방 상세를 볼 수 없다.")
  @Test
  void roomDetail() {
    assertDenied(() -> chatRoomDetailQueryService.findRoom(roomId, leftId));
  }

  @DisplayName("나간 사람은 대화를 볼 수 없다.")
  @Test
  void messages() {
    assertDenied(
        () -> chatMessageQueryService.findMessages(new MessageListQuery(roomId, null, 20), leftId));
  }

  @DisplayName("나간 사람은 메시지를 보낼 수 없다.")
  @Test
  void send() {
    assertDenied(
        () ->
            chatMessageSendService.send(
                roomId, leftId, UUID.randomUUID().toString(), "한 마디", null));
  }

  @DisplayName("나간 사람은 읽은 지점을 적을 수 없다.")
  @Test
  void markRead() {
    assertDenied(() -> chatRoomReadService.markRead(roomId, leftId, messageId));
  }

  @DisplayName("나간 사람은 사진 업로드 서명을 받을 수 없다.")
  @Test
  void issueUpload() {
    assertDenied(() -> chatImageService.issueUpload(roomId, leftId, "image/jpeg", 1024L));
  }

  @DisplayName("나간 사람도 그 방의 메시지를 신고할 수 있다.")
  @Test
  void report() {
    // 닫는 문 여섯 개 사이에 열린 문 하나가 있다는 것이 이 요구사항의 모양이다 (CH-21)
    assertThatCode(
            () ->
                reportCommandService.report(
                    new ReportCommand(
                        leftId, ReportTargetType.MESSAGE, messageId, ReportReason.ABUSE, null)))
        .doesNotThrowAnyException();
  }

  private static void assertDenied(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
  }

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
