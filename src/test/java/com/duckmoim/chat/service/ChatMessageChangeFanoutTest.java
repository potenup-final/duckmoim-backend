package com.duckmoim.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.duckmoim.chat.domain.MessageEvent;
import com.duckmoim.chat.domain.MessageStatus;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.AuthoredMessage;
import com.duckmoim.chat.infra.ChatFanout;
import com.duckmoim.chat.infra.ChatFanoutCodec;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.SignupStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

/**
 * 삭제 · 블라인드가 방에 알리는 자리 (STAR-147 · CH-12 · AD-09).
 *
 * <p><b>순서와 실패를 본다.</b> 사건이 Redis 를 돌아 연결에 닿는지는 {@code ChatStreamServiceTest} 가 실물 Redis 로 본다 —
 * 여기서는 「거절된 변경은 알리지 않는다」 · 「알리기가 터져도 변경은 성공이다」 두 규칙이다.
 *
 * <p><b>스프링을 띄우지 않는다.</b> {@code ChatMessageFanoutFailureTest} 와 같은 이유다 — {@code @SpringBootTest}
 * 컨텍스트를 하나 더 만들면 남의 테스트가 커넥션을 못 얻는다 (CLAUDE.md 「겪은 함정」).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("메시지 상태 변경 알림")
class ChatMessageChangeFanoutTest {

  private static final long ROOM_ID = 3L;
  private static final long SENDER_ID = 7L;
  private static final long MESSAGE_ID = 101L;
  private static final long ADMIN_ID = 1L;
  private static final String PAYLOAD = "{}";

  @Mock private ChatMessageRepository chatMessageRepository;
  @Mock private ChatFanoutCodec chatFanoutCodec;
  @Mock private ChatFanout chatFanout;
  @Mock private ChatMessageDeleteWriter chatMessageDeleteWriter;
  @Mock private AdminMessageBlindWriter adminMessageBlindWriter;

  private ChatMessageChangeFanout chatMessageChangeFanout;
  private ChatMessageDeleteService chatMessageDeleteService;
  private AdminMessageBlindService adminMessageBlindService;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(Instant.parse("2026-10-02T02:10:00Z"), ZoneOffset.UTC);
    chatMessageChangeFanout =
        new ChatMessageChangeFanout(chatMessageRepository, chatFanoutCodec, chatFanout, clock);
    chatMessageDeleteService =
        new ChatMessageDeleteService(chatMessageDeleteWriter, chatMessageChangeFanout);
    adminMessageBlindService =
        new AdminMessageBlindService(adminMessageBlindWriter, chatMessageChangeFanout);
  }

  /** 알리는 줄이 없던 것이 QA-EYE-04 의 원인이었다. */
  @DisplayName("메시지를 지우면 그 방에 상태 변경을 알린다.")
  @Test
  void delete_publishesChange() {
    givenAuthored(MessageStatus.DELETED);

    chatMessageDeleteService.delete(ROOM_ID, MESSAGE_ID, SENDER_ID);

    then(chatFanout).should().publish(ROOM_ID, PAYLOAD);
  }

  /** 거절된 삭제를 알리면 남의 화면에서 멀쩡한 말이 자리표시자로 바뀐다. */
  @DisplayName("삭제가 거절되면 알리지 않는다.")
  @Test
  void delete_doesNotPublishWhenRejected() {
    willThrow(new BusinessException(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND))
        .given(chatMessageDeleteWriter)
        .delete(ROOM_ID, MESSAGE_ID, SENDER_ID);

    assertThatThrownBy(() -> chatMessageDeleteService.delete(ROOM_ID, MESSAGE_ID, SENDER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND);
    then(chatFanout).should(never()).publish(anyLong(), anyString());
  }

  @DisplayName("관리자가 메시지를 가리면 그 방에 상태 변경을 알린다.")
  @Test
  void blind_publishesChange() {
    givenAuthored(MessageStatus.BLINDED);

    adminMessageBlindService.blind(MESSAGE_ID, ADMIN_ID);

    then(chatFanout).should().publish(ROOM_ID, PAYLOAD);
  }

  @DisplayName("블라인드가 거절되면 알리지 않는다.")
  @Test
  void blind_doesNotPublishWhenRejected() {
    willThrow(new BusinessException(ChatErrorCode.CHAT_MESSAGE_NOT_ACTIVE))
        .given(adminMessageBlindWriter)
        .blind(MESSAGE_ID, ADMIN_ID);

    assertThatThrownBy(() -> adminMessageBlindService.blind(MESSAGE_ID, ADMIN_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_MESSAGE_NOT_ACTIVE);
    then(chatFanout).should(never()).publish(anyLong(), anyString());
  }

  /**
   * <b>변경은 이미 커밋됐다.</b> 여기서 던지면 사용자는 실패를 보는데, 다시 누르면 「이미 지운 메시지」 404 다.
   *
   * <p>전송이 같은 구멍을 {@code ChatMessageFanoutFailureTest} 에서 막았다.
   */
  @DisplayName("알리기 위한 조회가 터져도 삭제는 성공으로 끝난다.")
  @Test
  void delete_succeedsWhenLookupFails() {
    willThrow(new QueryTimeoutException("커넥션 고갈"))
        .given(chatMessageRepository)
        .findAuthoredById(MESSAGE_ID);

    assertThatCode(() -> chatMessageDeleteService.delete(ROOM_ID, MESSAGE_ID, SENDER_ID))
        .doesNotThrowAnyException();
    then(chatFanout).should(never()).publish(anyLong(), anyString());
  }

  /**
   * <b>본문이 Redis 를 지나가지 않는다.</b> 응답을 그리는 자리도 한 번 더 끊지만, 팬아웃 payload 는 이 프로세스 밖으로 나간다 ({@code
   * AuthoredMessage#toEvent}).
   */
  @DisplayName("알리는 사건에 지운 메시지의 본문과 사진이 실리지 않는다.")
  @Test
  void delete_publishesWithoutContent() {
    givenAuthored(MessageStatus.DELETED);

    chatMessageDeleteService.delete(ROOM_ID, MESSAGE_ID, SENDER_ID);

    ArgumentCaptor<MessageEvent> captor = ArgumentCaptor.forClass(MessageEvent.class);
    then(chatFanoutCodec).should().encodeMessageChanged(captor.capture());
    assertThat(captor.getValue())
        .satisfies(
            event -> {
              assertThat(event.content()).isNull();
              assertThat(event.imageId()).isNull();
              assertThat(event.status()).isEqualTo(MessageStatus.DELETED);
            });
  }

  private void givenAuthored(MessageStatus status) {
    AuthoredMessage authored =
        new AuthoredMessage(
            MESSAGE_ID,
            ROOM_ID,
            SENDER_ID,
            "덕후1",
            null,
            LocalDateTime.of(2026, 10, 2, 2, 0),
            SignupStatus.ACTIVE,
            "지울 말",
            9L,
            status,
            LocalDateTime.of(2026, 10, 2, 1, 0));
    given(chatMessageRepository.findAuthoredById(MESSAGE_ID)).willReturn(Optional.of(authored));
    given(chatFanoutCodec.encodeMessageChanged(any())).willReturn(PAYLOAD);
  }
}
