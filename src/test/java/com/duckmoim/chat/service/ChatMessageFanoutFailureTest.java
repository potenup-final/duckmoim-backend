package com.duckmoim.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.duckmoim.chat.infra.ChatFanout;
import com.duckmoim.chat.infra.ChatFanoutCodec;
import com.duckmoim.chat.infra.ChatMessageRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

/**
 * <b>팬아웃 장애가 전송을 깨지 않는다</b> (PR #138 리뷰).
 *
 * <p>{@code ChatFanout} 의 자바독이 못박은 계약인데, <b>발행 직전의 조회 한 줄만 그 계약 밖에 있었다.</b> {@code Optional} 은 값이
 * 없는 경우만 다루지 조회 자체가 터지는 경우를 막지 않는다 — 커넥션 고갈이나 타임아웃은 {@code RuntimeException} 으로 올라온다.
 *
 * <p><b>새는 자리가 500 하나가 아니라는 것이 이 검사의 요지다.</b> 저장은 이미 커밋된 뒤라 그 예외는 재시도로도 복구되지 않는다 — 두 번째 요청은 「이미
 * 보냄」으로 즉시 반환해 발행 경로를 아예 지나지 않는다.
 *
 * <p><b>스프링을 띄우지 않는다.</b> 보는 것이 「조회가 터졌을 때 무엇을 하는가」 한 줄이라 컨텍스트가 필요하지 않고, {@code @SpringBootTest}
 * 컨텍스트를 하나 더 만들면 <b>남의 테스트가 커넥션을 못 얻는다</b> (CLAUDE.md 「겪은 함정」).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("팬아웃 실패와 전송")
class ChatMessageFanoutFailureTest {

  private static final long ROOM_ID = 3L;
  private static final long SENDER_ID = 7L;
  private static final long MESSAGE_ID = 101L;
  private static final String CLIENT_MESSAGE_ID = "abc";
  private static final String CONTENT = "8시에 3번 출구에서 봬요";

  @Mock private ChatMessageWriter chatMessageWriter;
  @Mock private ChatMessageRepository chatMessageRepository;
  @Mock private ChatFanout chatFanout;
  @Mock private ChatFanoutCodec chatFanoutCodec;

  @InjectMocks private ChatMessageSendService chatMessageSendService;

  /** 고치기 전에는 여기서 {@code QueryTimeoutException} 이 그대로 올라와 사용자에게 500 이 나갔다. */
  @DisplayName("팬아웃 조회가 터져도 전송은 성공으로 끝난다.")
  @Test
  void send_succeedsWhenFanoutLookupThrows() {
    givenFirstAttempt();
    willThrow(new QueryTimeoutException("커넥션 고갈"))
        .given(chatMessageRepository)
        .findAuthoredById(anyLong());

    assertThatCode(() -> send()).doesNotThrowAnyException();
  }

  /** 돌려주는 값도 정상 경로와 같아야 한다. 「보냈다」는 사실은 커밋으로 이미 확정됐다. */
  @DisplayName("팬아웃이 터져도 저장된 메시지를 그대로 돌려준다.")
  @Test
  void send_returnsSavedMessageWhenFanoutLookupThrows() {
    givenFirstAttempt();
    willThrow(new QueryTimeoutException("커넥션 고갈"))
        .given(chatMessageRepository)
        .findAuthoredById(anyLong());

    assertThat(send().messageId()).isEqualTo(MESSAGE_ID);
  }

  /** 삼킨 뒤에 발행을 시도하면 안 된다 — 실을 사건 자체를 못 만든 상태다. */
  @DisplayName("팬아웃 조회가 터지면 발행하지 않는다.")
  @Test
  void send_skipsPublishWhenFanoutLookupThrows() {
    givenFirstAttempt();
    willThrow(new QueryTimeoutException("커넥션 고갈"))
        .given(chatMessageRepository)
        .findAuthoredById(anyLong());

    send();

    then(chatFanout).should(never()).publish(anyLong(), any());
  }

  /** 조회가 비는 경우는 원래 계약대로 조용히 건너뛴다 — 예외를 잡느라 그쪽이 바뀌면 안 된다. */
  @DisplayName("팬아웃 조회가 비면 발행하지 않고 그대로 끝난다.")
  @Test
  void send_skipsPublishWhenMessageNotFound() {
    givenFirstAttempt();
    given(chatMessageRepository.findAuthoredById(anyLong())).willReturn(Optional.empty());

    assertThat(send().messageId()).isEqualTo(MESSAGE_ID);
    then(chatFanout).should(never()).publish(anyLong(), any());
  }

  private void givenFirstAttempt() {
    given(chatMessageRepository.findBySenderIdAndClientMessageId(anyLong(), anyString()))
        .willReturn(Optional.empty());
    given(chatMessageWriter.write(anyLong(), anyLong(), anyString(), anyString()))
        .willReturn(
            new SentMessage(
                MESSAGE_ID, ROOM_ID, SENDER_ID, CONTENT, LocalDateTime.of(2026, 10, 2, 11, 10)));
  }

  private SentMessage send() {
    return chatMessageSendService.send(ROOM_ID, SENDER_ID, CLIENT_MESSAGE_ID, CONTENT);
  }
}
