package com.duckmoim.chat.service;

import com.duckmoim.chat.infra.AuthoredMessage;
import com.duckmoim.chat.infra.ChatMessageRepository;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 끊겨 있던 동안 못 받은 것을 읽는다 (CH-11).
 *
 * <p>검증 기준이 <b>「끊고 그 사이 N건을 보낸 뒤 재연결 → 유실 0건」</b>이고, 이 클래스가 그 N건을 찾는 자리다.
 *
 * <p><b>빈을 나눈 이유는 {@link ChatRoomMembershipReader} 와 같다</b> (PR #138 리뷰). {@code
 * ChatStreamService#open} 에 {@code @Transactional} 을 걸면 그 메서드가 끝날 때까지 DB 커넥션을 쥐는데, 그 안에 Redis 구독이
 * 들어 있다 — {@code RedisMessageListenerContainer} 는 구독 등록을 기본 2초까지 기다린다. 자기 클래스의 메서드를 부르면 프록시를 지나지 않아
 * 애너테이션이 안 걸리므로 별 빈이어야 한다.
 *
 * <p><b>멤버 판정을 다시 하지 않는다.</b> 부르는 쪽이 이미 했고 (I-18), 판정을 두 곳에 두면 한쪽만 고치는 날이 온다.
 */
@Service
@RequiredArgsConstructor
public class ChatStreamReplayReader {

  /**
   * 재연결 지점보다 이만큼 앞에서부터 읽는다.
   *
   * <p><b>{@code id} 는 삽입 순서이지 커밋 순서가 아니다</b> ({@code MessageCursor} 자바독 · PR #131 리뷰). {@code
   * AUTO_INCREMENT} 는 INSERT 때 번호를 주고 COMMIT 은 그 뒤라, 낮은 번호가 늦게 커밋되는 창이 있다.
   *
   * <pre>
   * tx A  INSERT → id 100 ─────────────┐ (커밋 전. 아무도 못 본다)
   * tx B  INSERT → id 101 → COMMIT     │  클라이언트가 본 마지막 = 101
   * tx A  ──────────────────── COMMIT     100 이 생긴다
   *       id &gt; 101 로 이어 읽으면        → 100 은 영영 안 온다
   * </pre>
   *
   * <p>그래서 조금 앞에서부터 다시 보내고 <b>클라이언트가 {@code messageId} 로 거른다.</b> 클라이언트는 이미 {@code clientMessageId}
   * 로 멱등 처리를 하고 있어 중복 제거가 새 규칙이 아니다.
   *
   * <p><b>{@code createdAt} 을 커서에 더해도 풀리지 않는다.</b> 그 값도 {@code BaseEntity} 가 INSERT 시점에 박는다.
   *
   * <p><b>정확한 해법은 아니다.</b> 이 값은 「같은 방에 동시에 열려 있는 INSERT 개수」의 상한을 가정한 것이고, 그것을 넘으면 다시 한 건이 샌다. 완전한
   * 해법은 커밋 시점에 매기는 별도 순번인데 지금 동시 전송 수가 한 자리라 치를 값이 아니다.
   */
  static final int BACKTRACK = 20;

  /**
   * 한 번에 되돌려줄 수 있는 최대 건수.
   *
   * <p>무한이면 <b>며칠 끊겼던 클라이언트 하나가 수만 건을 끌어간다</b> — 그 한 명의 재연결이 인스턴스의 메모리와 선로를 먹는다. 넘치면 재전송 대신 알린다
   * ({@link MissedMessages#tooMany}).
   *
   * <p><b>실제로 되돌려줄 수 있는 건수는 이 값보다 적다</b> (PR #142 리뷰). {@link #BACKTRACK} 만큼 뒤로 물러서서 읽으므로 그 구간의 행이
   * 먼저 이 상한을 먹는다.
   *
   * <pre>
   * 읽히는 행 수  ≈  되돌아간 구간의 행 (최대 20)  +  못 받은 건수
   * 그래서 못 받은 것이 80건만 넘어도 따라잡기로 넘어간다
   * </pre>
   *
   * <p><b>「최대 20」인 것은 {@code id} 가 방마다 따로 매겨지지 않기 때문이다.</b> 번호는 표 전체에서 하나의 수열이라, 다른 방이 바쁘면 20번 물러서도
   * 이 방의 행은 그보다 적다.
   *
   * <p><b>버그가 아니라 안전한 방향이다.</b> 넘치면 목록 API 로 폴백하므로 못 받은 것이 사라지지 않는다 — 다만 이 숫자를 고칠 때 <b>상한이 그대로 상한이
   * 아니라는 것</b>을 알고 고쳐야 한다.
   */
  static final int LIMIT = 100;

  private final ChatMessageRepository chatMessageRepository;
  private final Clock clock;

  /**
   * 그 방에서 {@code lastEventId} 뒤에 생긴 것을 오래된 것부터 읽는다.
   *
   * <p><b>{@link #LIMIT} + 1 건을 읽는다.</b> 넘치는지를 COUNT 없이 판정하기 위해서다 — 목록 조회가 {@code size + 1} 로 다음
   * 페이지 유무를 판정하는 것과 같은 수법이다 (CH-09).
   *
   * <p>사건으로 바꾸는 일은 {@code AuthoredMessage#toEvent} 가 한다 — 팬아웃과 같은 자리다.
   */
  @Transactional(readOnly = true)
  public MissedMessages readSince(Long roomId, Long lastEventId) {
    long from = Math.max(0L, lastEventId - BACKTRACK);

    List<AuthoredMessage> read = chatMessageRepository.findAfter(roomId, from, LIMIT + 1);
    if (read.size() > LIMIT) {
      return MissedMessages.tooMany();
    }

    return MissedMessages.of(read.stream().map(message -> message.toEvent(clock)).toList());
  }
}
