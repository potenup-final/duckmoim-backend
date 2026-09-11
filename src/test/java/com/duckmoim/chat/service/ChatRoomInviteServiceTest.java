package com.duckmoim.chat.service;

import static com.duckmoim.companion.CommentFixture.aComment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.ChatRoomMember;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.domain.CommentStatus;
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
 * 초대의 검증 기준 중 방 밖을 읽어야 하는 것 (CH-02).
 *
 * <p>명세의 셋을 그대로 옮겼다 — 「방장 아닌 계정의 초대 시 403」 · 「댓글을 쓰지 않은 유저 초대 시 400」 · 「초대 직후 멤버 목록에 포함」.
 *
 * <p><b>인원 상한(CH-03)과 재초대 차단(CH-02a)은 여기 없다.</b> 방 하나로 판정이 끝나 {@code ChatRoomTest} 가 본다. 상한은 특히 그렇다
 * — 여기서 보려면 댓글 99개를 넣어야 하고, 그러고도 확인되는 것은 세는 자리가 아니라 픽스처다.
 *
 * <p><b>모집글 작성부터 지난다.</b> 방이 그 부수효과로 생기고 (CH-01) 방장이 누구인지가 모집글에만 있다.
 */
@SpringBootTest
@Transactional
class ChatRoomInviteServiceTest {

  private static final long HOST_ID = 7L;
  private static final long COMMENTER_ID = 11L;
  private static final long STRANGER_ID = 12L;

  private static final BigDecimal LAT = new BigDecimal("37.5256381");
  private static final BigDecimal LNG = new BigDecimal("126.9289384");

  @Autowired private ChatRoomInviteService chatRoomInviteService;
  @Autowired private CompanionPostCommandService companionPostCommandService;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @DisplayName("방장이 댓글 작성자를 초대하면 곧바로 멤버 목록에 든다.")
  @Test
  void invite() {
    long postId = openPostWithCommentBy(COMMENTER_ID);

    chatRoomInviteService.invite(postId, COMMENTER_ID, HOST_ID);

    assertThat(currentMemberIdsOf(postId)).containsExactlyInAnyOrder(HOST_ID, COMMENTER_ID);
  }

  @DisplayName("초대 결과로 방 번호와 초대 후 멤버 수를 준다.")
  @Test
  void inviteReturnsRoom() {
    long postId = openPostWithCommentBy(COMMENTER_ID);
    ChatRoom room = chatRoomRepository.findByPostId(postId).orElseThrow();

    ChatRoomInvitation invitation = chatRoomInviteService.invite(postId, COMMENTER_ID, HOST_ID);

    assertThat(invitation).isEqualTo(new ChatRoomInvitation(room.getId(), 2));
  }

  @DisplayName("방장이 아닌 사람의 초대는 403 이다.")
  @Test
  void inviteByNonHost() {
    long postId = openPostWithCommentBy(COMMENTER_ID);

    assertThatThrownBy(() -> chatRoomInviteService.invite(postId, COMMENTER_ID, STRANGER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_HOST);
  }

  /** 댓글 작성자 본인이 자기를 부르는 것도 여기 걸린다. 방장 판정이 먼저라 400 이 아니라 403 이다. */
  @DisplayName("댓글 작성자가 스스로를 초대해도 403 이다.")
  @Test
  void inviteBySelf() {
    long postId = openPostWithCommentBy(COMMENTER_ID);

    assertThatThrownBy(() -> chatRoomInviteService.invite(postId, COMMENTER_ID, COMMENTER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_HOST);
  }

  @DisplayName("댓글을 쓰지 않은 사람을 초대하면 400 이다.")
  @Test
  void inviteNonCommenter() {
    long postId = openPostWithCommentBy(COMMENTER_ID);

    assertThatThrownBy(() -> chatRoomInviteService.invite(postId, STRANGER_ID, HOST_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_INVITEE_NOT_COMMENTER);
  }

  /** 지워진 댓글에는 초대 버튼이 없다 (CM-11). 그 경로로 온 요청은 댓글을 안 쓴 것과 같게 답한다. */
  @DisplayName("댓글을 지운 사람은 초대 대상이 아니다.")
  @Test
  void inviteAuthorOfDeletedComment() {
    WrittenCompanionPost written = companionPostCommandService.create(command());
    aComment()
        .postId(written.id())
        .authorId(COMMENTER_ID)
        .status(CommentStatus.DELETED)
        .insert(jdbcTemplate);

    assertThatThrownBy(() -> chatRoomInviteService.invite(written.id(), COMMENTER_ID, HOST_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_INVITEE_NOT_COMMENTER);
  }

  /** 방을 먼저 찾으므로, 없는 모집글은 방장 판정 전에 404 로 끝난다. */
  @DisplayName("없는 모집글로 초대하면 404 다.")
  @Test
  void inviteOnMissingPost() {
    assertThatThrownBy(() -> chatRoomInviteService.invite(404_404L, COMMENTER_ID, HOST_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_FOUND);
  }

  private long openPostWithCommentBy(long authorId) {
    WrittenCompanionPost written = companionPostCommandService.create(command());
    aComment().postId(written.id()).authorId(authorId).insert(jdbcTemplate);

    return written.id();
  }

  private List<Long> currentMemberIdsOf(long postId) {
    return chatRoomRepository.findByPostId(postId).orElseThrow().currentMembers().stream()
        .map(ChatRoomMember::getUserId)
        .toList();
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
