package com.duckmoim.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.ChatRoomMember;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.companion.service.CompanionPostCommandService;
import com.duckmoim.companion.service.CompanionPostWriteCommand;
import com.duckmoim.companion.service.WrittenCompanionPost;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모집글을 쓰면 방이 함께 생긴다 (CH-01).
 *
 * <p><b>모집글 작성부터 지난다.</b> 검증 기준이 「모집글 작성 직후 방 조회」 라서 방을 직접 만들어 보는 것으로는 확인되지 않는다 — 이 티켓이 붙이는 것이 방 생성
 * 코드가 아니라 <b>작성과 방 사이의 연결</b>이고, 연결이 끊기면 방 생성 코드는 멀쩡한 채로 방만 안 생긴다.
 *
 * <p><b>실제 MySQL 로 돈다.</b> 두 컨텍스트가 한 트랜잭션을 공유하는지가 이 티켓의 핵심 결정이라 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」의
 * 예외) 저장을 지나야 확인된다.
 *
 * <p><b>「방 조회 시 200」의 HTTP 부분은 여기 없다.</b> 방 상세 조회가 CH-06 이고 이 티켓은 엔드포인트를 열지 않는다.
 *
 * <p>트랜잭션 경계 자체는 {@code ChatRoomOpenTransactionTest} 가 본다.
 */
@SpringBootTest
@Transactional
class ChatRoomOpenServiceTest {

  private static final long HOST_ID = 7L;

  private static final BigDecimal LAT = new BigDecimal("37.5256381");
  private static final BigDecimal LNG = new BigDecimal("126.9289384");

  @Autowired private CompanionPostCommandService companionPostCommandService;
  @Autowired private ChatRoomRepository chatRoomRepository;

  @DisplayName("모집글을 작성하면 채팅방이 함께 생긴다.")
  @Test
  void openForOnPostOpened() {
    WrittenCompanionPost written = companionPostCommandService.create(command());

    assertThat(chatRoomRepository.findByPostId(written.id())).isPresent();
  }

  @DisplayName("모집글과 함께 생긴 방의 멤버는 방장 하나다.")
  @Test
  void openForJoinsHostAlone() {
    WrittenCompanionPost written = companionPostCommandService.create(command());

    ChatRoom room = chatRoomRepository.findByPostId(written.id()).orElseThrow();
    assertThat(room.currentMembers())
        .extracting(ChatRoomMember::getUserId)
        .containsExactly(HOST_ID);
  }

  @DisplayName("모집글마다 방이 따로 생긴다.")
  @Test
  void openForOpensOneRoomPerPost() {
    WrittenCompanionPost first = companionPostCommandService.create(command());
    WrittenCompanionPost second = companionPostCommandService.create(command());

    ChatRoom firstRoom = chatRoomRepository.findByPostId(first.id()).orElseThrow();
    ChatRoom secondRoom = chatRoomRepository.findByPostId(second.id()).orElseThrow();
    assertThat(firstRoom.getId()).isNotEqualTo(secondRoom.getId());
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
