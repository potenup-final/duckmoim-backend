package com.duckmoim.chat.service;

import static com.duckmoim.companion.CommentFixture.aComment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.ChatRoomMember;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.service.CompanionPostCommandService;
import com.duckmoim.companion.service.CompanionPostWriteCommand;
import com.duckmoim.companion.service.WrittenCompanionPost;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 퇴장의 검증 기준 중 방 밖을 읽어야 하는 것 (CH-04).
 *
 * <p>명세의 한 줄이 「방장의 퇴장 시도 시 409」 이고, 방장이 누구인지는 모집글에만 있어 방 하나로는 판정이 끝나지 않는다. 비멤버·이미 나간 사람의 403 은 방
 * 안에서 끝나 {@code ChatRoomTest} 가 본다 — 여기서는 <b>모집글에서 읽은 방장이 실제로 넘어가는지</b>를 본다.
 *
 * <p><b>퇴장이 남긴 결과도 여기서 본다</b> — 재초대 409(CH-02a) · 목록에서 사라짐(CH-05) · 상세 403(CH-18). 셋 다 <b>퇴장이 있어야
 * 비로소 닿는 자리</b>라, 각 티켓이 아니라 이 티켓이 확인한다. 특히 CH-02a 는 퇴장이 행을 지우는 순간 조용히 200 으로 열린다.
 *
 * <p><b>모집글 작성부터 지난다.</b> 방이 그 부수효과로 생기고 (CH-01) 방장이 누구인지가 모집글에만 있다. {@code
 * ChatRoomInviteServiceTest} 와 같은 구성이라 컨텍스트를 나눠 쓴다.
 */
@SpringBootTest
@Transactional
class ChatRoomLeaveServiceTest {

  private static final long HOST_ID = 7L;
  private static final long MEMBER_ID = 11L;
  private static final long STRANGER_ID = 12L;

  private static final BigDecimal LAT = new BigDecimal("37.5256381");
  private static final BigDecimal LNG = new BigDecimal("126.9289384");

  @Autowired private ChatRoomLeaveService chatRoomLeaveService;
  @Autowired private ChatRoomInviteService chatRoomInviteService;
  @Autowired private ChatRoomListQueryService chatRoomListQueryService;
  @Autowired private ChatRoomDetailQueryService chatRoomDetailQueryService;
  @Autowired private CompanionPostCommandService companionPostCommandService;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @DisplayName("초대받은 멤버가 나가면 멤버 목록에서 빠진다.")
  @Test
  void leave() {
    long roomId = roomWithMember();

    chatRoomLeaveService.leave(roomId, MEMBER_ID);

    assertThat(currentMemberIdsOf(roomId)).containsExactly(HOST_ID);
  }

  /**
   * 퇴장 이력이 남는지를 service 경로로도 본다 (I-19).
   *
   * <p>도메인이 행을 남겨도 여기서 지우면 재초대 차단(CH-02a)이 무너지므로, 저장소를 다시 읽어 행이 그대로인지 확인한다.
   */
  @DisplayName("나간 사람의 행은 남고 나간 시각이 찬다.")
  @Test
  void leaveKeepsRow() {
    long roomId = roomWithMember();

    chatRoomLeaveService.leave(roomId, MEMBER_ID);

    ChatRoomMember left = memberRowOf(roomId, MEMBER_ID);
    assertThat(left.getLeftAt()).isNotNull();
    assertThat(left.isJoined()).isFalse();
  }

  /** 명세의 검증 기준. 방장인지는 모집글을 읽어야 알 수 있어 이 경로가 판정에 필요하다. */
  @DisplayName("방장은 자기 모집글의 방을 나갈 수 없다.")
  @Test
  void leave_requesterIsHost() {
    long roomId = roomWithMember();

    assertThatThrownBy(() -> chatRoomLeaveService.leave(roomId, HOST_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_HOST_CANNOT_LEAVE);
  }

  @DisplayName("멤버가 아닌 사람의 퇴장은 403 이다.")
  @Test
  void leave_requesterIsNotMember() {
    long roomId = roomWithMember();

    assertThatThrownBy(() -> chatRoomLeaveService.leave(roomId, STRANGER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
  }

  @DisplayName("없는 방을 나가려 하면 404 다.")
  @Test
  void leave_roomIsMissing() {
    assertThatThrownBy(() -> chatRoomLeaveService.leave(404_404L, MEMBER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_FOUND);
  }

  /**
   * CH-02a 의 검증 기준 「퇴장한 유저를 다시 초대하면 409」.
   *
   * <p><b>이 검사가 성립하려면 퇴장이 있어야 한다.</b> {@code ChatRoomTest} 에도 같은 규칙이 있지만 그쪽은 방 하나 안의 이야기이고, 여기서는
   * 나가기와 초대가 <b>각자의 트랜잭션</b>을 지나 DB 에 남은 이력으로 판정되는지를 본다 — 퇴장이 행을 지우면 초대받은 적 없는 사람과 같아져 이 자리가 200 으로
   * 열린다.
   */
  @DisplayName("퇴장한 유저를 다시 초대하면 409 다.")
  @Test
  void leftMemberCannotBeInvitedAgain() {
    WrittenCompanionPost written = companionPostCommandService.create(command());
    aComment().postId(written.id()).authorId(MEMBER_ID).insert(jdbcTemplate);
    chatRoomInviteService.invite(written.id(), MEMBER_ID, HOST_ID);
    chatRoomLeaveService.leave(roomIdOf(written.id()), MEMBER_ID);

    assertThatThrownBy(() -> chatRoomInviteService.invite(written.id(), MEMBER_ID, HOST_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_MEMBER_LEFT);
  }

  /** CH-05 의 검증 기준 「나간 방은 목록에 없다」. 목록은 전용 쿼리라 애그리게이트와 따로 걸러야 한다. */
  @DisplayName("나간 방은 내 방 목록에 없다.")
  @Test
  void leftRoomIsNotListed() {
    long roomId = roomWithMember();

    chatRoomLeaveService.leave(roomId, MEMBER_ID);

    assertThat(chatRoomListQueryService.findRooms(MEMBER_ID))
        .extracting(ChatRoomSummaryView::roomId)
        .doesNotContain(roomId);
  }

  /** CH-18 의 검증 기준 중 방 화면 쪽. 나간 사람은 멤버가 아니라 (I-18) 상세도 열리지 않는다. */
  @DisplayName("나간 사람은 그 방의 상세를 볼 수 없다.")
  @Test
  void leftMemberCannotReadRoom() {
    long roomId = roomWithMember();

    chatRoomLeaveService.leave(roomId, MEMBER_ID);

    assertThatThrownBy(() -> chatRoomDetailQueryService.findRoom(roomId, MEMBER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
  }

  /** 방장 + 초대받은 멤버 하나짜리 방. 초대는 댓글 작성자만 받으므로 댓글부터 넣는다 (CH-02). */
  private long roomWithMember() {
    WrittenCompanionPost written = companionPostCommandService.create(command());
    aComment().postId(written.id()).authorId(MEMBER_ID).insert(jdbcTemplate);
    chatRoomInviteService.invite(written.id(), MEMBER_ID, HOST_ID);

    return roomIdOf(written.id());
  }

  private long roomIdOf(long postId) {
    return chatRoomRepository.findByPostId(postId).orElseThrow().getId();
  }

  private List<Long> currentMemberIdsOf(long roomId) {
    return roomOf(roomId).currentMembers().stream().map(ChatRoomMember::getUserId).toList();
  }

  private ChatRoomMember memberRowOf(long roomId, long userId) {
    return roomOf(roomId).getMembers().stream()
        .filter(member -> member.getUserId().equals(userId))
        .findFirst()
        .orElseThrow();
  }

  private ChatRoom roomOf(long roomId) {
    return chatRoomRepository.findById(roomId).orElseThrow();
  }

  private static CompanionPostWriteCommand command() {
    return new CompanionPostWriteCommand(
        HOST_ID,
        "에이티즈 팝업 같이 가실 분",
        "굿즈 교환도 해요",
        null,
        OffsetDateTime.parse("2026-10-01T09:00:00+09:00"),
        "더현대 서울 지하 1층 팝업 아이코닉",
        LAT,
        LNG,
        null);
  }
}
