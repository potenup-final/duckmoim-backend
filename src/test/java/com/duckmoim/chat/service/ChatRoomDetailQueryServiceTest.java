package com.duckmoim.chat.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.AuthorDisplay;
import com.duckmoim.identity.domain.SignupStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방 상세의 검증 기준 (CH-06).
 *
 * <p>실제 MySQL 로 돈다. {@code @Transactional} 로 시드를 되돌린다.
 *
 * <p><b>시계를 고정한다.</b> writable 판정이 현재 시각을 쓴다 (CH-08).
 */
@SpringBootTest
@Transactional
class ChatRoomDetailQueryServiceTest {

  private static final LocalDateTime MEET_AT = LocalDateTime.of(2026, 10, 1, 9, 0);
  private static final LocalDateTime NOW = MEET_AT.plusDays(1);

  @TestConfiguration
  static class FixedClockConfig {

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }
  }

  @Autowired private ChatRoomDetailQueryService chatRoomDetailQueryService;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private JdbcTemplate jdbc;

  @DisplayName("방 상세를 조회한다.")
  @Test
  void findRoom() {
    // given
    long hostId = aUser().nickname("방장픽스처2").insert(jdbc);
    long guestId = aUser().insert(jdbc);
    long postId = aCompanionPost().title("CH-06 서비스 픽스처").meetAt(MEET_AT).insert(jdbc);
    ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, hostId));
    room.invite(guestId);
    chatRoomRepository.saveAndFlush(room);

    // when
    ChatRoomDetailView view = chatRoomDetailQueryService.findRoom(room.getId(), hostId);

    // then
    assertThat(view.postTitle()).isEqualTo("CH-06 서비스 픽스처");
    assertThat(view.meetAt()).isEqualTo(MEET_AT);
    assertThat(view.members())
        .extracting(ChatRoomMemberView::userId)
        .containsExactly(hostId, guestId);
  }

  @DisplayName("멤버가 아니면 403 이다.")
  @Test
  void findRoom_notMember() {
    // given
    long hostId = aUser().insert(jdbc);
    long strangerId = aUser().insert(jdbc);
    long postId = aCompanionPost().title("CH-06 서비스 픽스처 비멤버").meetAt(MEET_AT).insert(jdbc);
    ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, hostId));

    // when & then
    assertThatThrownBy(() -> chatRoomDetailQueryService.findRoom(room.getId(), strangerId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
  }

  @DisplayName("멤버가 방장뿐이어도(대화가 없어도) 방 상세가 열린다.")
  @Test
  void findRoom_hostOnly() {
    // given
    long hostId = aUser().insert(jdbc);
    long postId = aCompanionPost().title("CH-06 서비스 픽스처 방장뿐").meetAt(MEET_AT).insert(jdbc);
    ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, hostId));

    // when
    ChatRoomDetailView view = chatRoomDetailQueryService.findRoom(room.getId(), hostId);

    // then
    assertThat(view.members()).extracting(ChatRoomMemberView::userId).containsExactly(hostId);
  }

  @DisplayName("탈퇴한 멤버는 자리표시자로 나간다.")
  @Test
  void findRoom_withdrawnMember() {
    // given
    long hostId = aUser().insert(jdbc);
    long withdrawnGuestId = aUser().status(SignupStatus.WITHDRAWN).insert(jdbc);
    long postId = aCompanionPost().title("CH-06 서비스 픽스처 탈퇴멤버").meetAt(MEET_AT).insert(jdbc);
    ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, hostId));
    room.invite(withdrawnGuestId);
    chatRoomRepository.saveAndFlush(room);

    // when
    ChatRoomDetailView view = chatRoomDetailQueryService.findRoom(room.getId(), hostId);

    // then
    assertThat(view.members())
        .filteredOn(member -> member.userId().equals(withdrawnGuestId))
        .singleElement()
        .satisfies(
            member -> {
              assertThat(member.nickname()).isEqualTo(AuthorDisplay.WITHDRAWN_NICKNAME);
              assertThat(member.profileImageUrl()).isNull();
            });
  }

  @DisplayName("만남시각 + 7일 이내면 채팅 가능이다.")
  @Test
  void findRoom_writableWithinWindow() {
    // given
    long hostId = aUser().insert(jdbc);
    LocalDateTime meetAt = NOW.minusDays(1);
    long postId = aCompanionPost().title("CH-06 서비스 픽스처 쓰기가능").meetAt(meetAt).insert(jdbc);
    ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, hostId));

    // when
    ChatRoomDetailView view = chatRoomDetailQueryService.findRoom(room.getId(), hostId);

    // then
    assertThat(view.writable()).isTrue();
  }

  @DisplayName("만남시각 + 7일이 지나면 읽기 전용이다.")
  @Test
  void findRoom_notWritableAfterWindow() {
    // given
    long hostId = aUser().insert(jdbc);
    LocalDateTime meetAt = NOW.minusDays(ChatRoom.WRITABLE_WINDOW_DAYS).minusDays(1);
    long postId = aCompanionPost().title("CH-06 서비스 픽스처 읽기전용").meetAt(meetAt).insert(jdbc);
    ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, hostId));

    // when
    ChatRoomDetailView view = chatRoomDetailQueryService.findRoom(room.getId(), hostId);

    // then
    assertThat(view.writable()).isFalse();
  }

  @DisplayName("방이 없으면 404 다.")
  @Test
  void findRoom_notFound() {
    assertThatThrownBy(() -> chatRoomDetailQueryService.findRoom(999_999L, 1L))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_FOUND);
  }
}
