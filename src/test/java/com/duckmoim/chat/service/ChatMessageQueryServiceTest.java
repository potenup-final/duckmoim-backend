package com.duckmoim.chat.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.Message;
import com.duckmoim.chat.domain.MessageCursor;
import com.duckmoim.chat.domain.MessageListQuery;
import com.duckmoim.chat.domain.MessageStatus;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.AuthorDisplay;
import com.duckmoim.identity.domain.SignupStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 목록 조회의 검증 기준 (CH-09) — <b>페이지 경계에서 누락·중복 없음.</b>
 *
 * <p>그 한 줄이 이 클래스의 전부이고, 나머지는 그 한 줄이 성립하는 조건들이다 — 멤버만 볼 수 있고 (I-18 · CH-18), 지운 메시지가 목록에 남고
 * (CH-12), OFFSET 을 쓰지 않는다.
 *
 * <p><b>경계를 실제로 지나게 만든다.</b> 「누락·중복 없음」은 한 페이지만 읽어서는 증명되지 않는다. 크기를 작게 잡아 여러 장을 이어 읽고 <b>모아 놓은 것이 처음
 * 넣은 것과 같은지</b>를 본다 — 한 건이라도 빠지거나 겹치면 그 비교에서 걸린다.
 */
@SpringBootTest
@Transactional
@DisplayName("메시지 목록 조회")
class ChatMessageQueryServiceTest {

  private static final LocalDateTime MEET_AT_UTC = LocalDateTime.of(2026, 10, 1, 9, 0);

  @Autowired private ChatMessageQueryService chatMessageQueryService;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private ChatMessageRepository chatMessageRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

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

  @DisplayName("방의 메시지를 최신부터 준다.")
  @Test
  void findMessages() {
    send("첫째");
    send("둘째");
    send("셋째");

    List<MessageView> items = page(null, 10).items();

    assertThat(items).extracting(MessageView::content).containsExactly("셋째", "둘째", "첫째");
  }

  /**
   * <b>이 검사가 CH-09 의 검증 기준이다.</b>
   *
   * <p>7건을 크기 3으로 이어 읽는다 — 세 페이지가 나오고 마지막이 1건이라 <b>경계가 두 번</b> 지나간다. 커서의 부등호가 {@code <=} 로 잘못되어 있으면
   * 중복이 생기고, 다음 페이지 커서를 한 칸 당기면 누락이 생긴다. 둘 다 여기서 걸린다.
   */
  @DisplayName("페이지를 이어 읽으면 누락도 중복도 없다.")
  @Test
  void findMessages_hasNoGapOrDuplicateAcrossPages() {
    List<String> sent = new ArrayList<>();
    for (int i = 1; i <= 7; i++) {
      sent.add("메시지 " + i);
      send("메시지 " + i);
    }

    List<String> read = new ArrayList<>();
    MessageCursor cursor = null;
    boolean hasNext = true;

    while (hasNext) {
      MessageSlice slice = page(cursor, 3);
      slice.items().forEach(item -> read.add(item.content()));

      hasNext = slice.hasNext();
      cursor = slice.nextCursor();
    }

    // 최신순으로 읽었으므로 보낸 순서의 역순이어야 한다.
    List<String> newestFirst = new ArrayList<>(sent);
    Collections.reverse(newestFirst);

    assertThat(read).containsExactlyElementsOf(newestFirst);
  }

  /** 마지막 페이지는 이어 읽을 곳이 없다. 커서를 남기면 클라이언트가 빈 페이지를 한 번 더 부른다. */
  @DisplayName("마지막 페이지는 다음 커서를 주지 않는다.")
  @Test
  void findMessages_lastPageHasNoCursor() {
    send("하나");
    send("둘");

    MessageSlice slice = page(null, 10);

    assertThat(slice.hasNext()).isFalse();
    assertThat(slice.nextCursor()).isNull();
  }

  @DisplayName("메시지가 없는 방도 빈 목록으로 열린다.")
  @Test
  void findMessages_emptyRoom() {
    MessageSlice slice = page(null, 10);

    assertThat(slice.items()).isEmpty();
    assertThat(slice.hasNext()).isFalse();
  }

  /** CH-12 의 「자리표시자 유지」. 질의가 걸러 내면 지운 자리가 통째로 사라져 앞뒤 대화가 붙어 버린다. */
  @DisplayName("지운 메시지도 목록에 남고 본문만 비어 있다.")
  @Test
  void findMessages_keepsDeletedAsPlaceholder() {
    long deletedId = send("지울 말");
    send("남길 말");
    chatMessageRepository.findById(deletedId).orElseThrow().deleteBy(memberId);
    chatMessageRepository.flush();

    List<MessageView> items = page(null, 10).items();

    assertThat(items).hasSize(2);
    assertThat(items)
        .filteredOn(item -> item.messageId().equals(deletedId))
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.content()).isNull();
              assertThat(item.status()).isEqualTo(MessageStatus.DELETED);
            });
  }

  @DisplayName("보낸 사람의 닉네임이 메시지마다 실린다.")
  @Test
  void findMessages_carriesSender() {
    send("안녕하세요");

    MessageView item = page(null, 10).items().get(0);

    assertThat(item.senderId()).isEqualTo(memberId);
    assertThat(item.sender().nickname()).startsWith("멤버");
  }

  /**
   * 탈퇴한 사람의 옛 메시지 (AU-11).
   *
   * <p>익명화를 경로마다 적으면 하나를 빠뜨리는 날 그 경로로만 실명이 샌다 — {@code AuthorDisplay} 가 그 판정을 한 곳에 모은 이유이고, 새로 여는 이
   * 경로가 그 자리를 지나는지 확인한다.
   */
  @DisplayName("탈퇴한 사람의 메시지는 자리표시자 닉네임으로 나간다.")
  @Test
  void findMessages_anonymizesWithdrawnSender() {
    send("탈퇴 전에 남긴 말");
    jdbcTemplate.update(
        "UPDATE user SET status = ? WHERE id = ?", SignupStatus.WITHDRAWN.name(), memberId);

    MessageView item = page(null, 10).items().get(0);

    assertThat(item.sender().nickname()).isEqualTo(AuthorDisplay.WITHDRAWN_NICKNAME);
    assertThat(item.sender().profileImageUrl()).isNull();
  }

  @DisplayName("방 멤버가 아니면 403 이다.")
  @Test
  void findMessages_rejectsNonMember() {
    assertThatThrownBy(
            () ->
                chatMessageQueryService.findMessages(
                    new MessageListQuery(roomId, null, 10), strangerId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
  }

  @DisplayName("없는 방을 조회하면 404 다.")
  @Test
  void findMessages_rejectsMissingRoom() {
    assertThatThrownBy(
            () ->
                chatMessageQueryService.findMessages(
                    new MessageListQuery(404404L, null, 10), memberId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_FOUND);
  }

  /** 남의 방 메시지가 섞이면 본문이 통째로 새는 것이라 I-18 이 깨진다. */
  @DisplayName("다른 방의 메시지는 섞이지 않는다.")
  @Test
  void findMessages_isScopedToRoom() {
    send("이 방의 말");

    long otherPostId = aCompanionPost().hostId(memberId).meetAt(MEET_AT_UTC).insert(jdbcTemplate);
    ChatRoom otherRoom = ChatRoom.openFor(otherPostId, memberId);
    long otherRoomId = chatRoomRepository.saveAndFlush(otherRoom).getId();
    chatMessageRepository.saveAndFlush(
        Message.send(otherRoomId, memberId, UUID.randomUUID().toString(), "남의 방 말", null));

    List<MessageView> items = page(null, 10).items();

    assertThat(items).extracting(MessageView::content).containsExactly("이 방의 말");
  }

  private MessageSlice page(MessageCursor cursor, int size) {
    return chatMessageQueryService.findMessages(
        new MessageListQuery(roomId, cursor, size), memberId);
  }

  /** 보낸 메시지의 번호를 준다. */
  private long send(String content) {
    Message message =
        chatMessageRepository.saveAndFlush(
            Message.send(roomId, memberId, UUID.randomUUID().toString(), content, null));

    return message.getId();
  }

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
