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
   * tx A  ──────────────────── COMMIT     100 이 이제야 보인다
   *       id &gt; 101 로 이어 읽으면        → 100 은 영영 안 온다
   * </pre>
   *
   * <p>그래서 조금 앞에서부터 다시 보내고 <b>클라이언트가 {@code messageId} 로 거른다.</b> 클라이언트는 이미 {@code clientMessageId}
   * 로 멱등 처리를 하고 있어 중복 제거가 새 규칙이 아니다. {@code createdAt} 을 커서에 더해도 풀리지 않는다 — 그 값도 {@code BaseEntity}
   * 가 INSERT 시점에 박는다.
   *
   * <p><b>단위는 「방」이 아니라 「표 전체」다</b> (PR #142 리뷰). 처음에 이 값을 <i>"같은 방에 동시에 열려 있는 INSERT 개수"</i> 로 적었는데
   * 틀렸다 — {@code chat_message.id} 는 표 하나에서 발급되는 수열이라 창을 만드는 것은 <b>서비스 전체에서 동시에 열려 있는 INSERT</b> 다.
   * 아래 {@link #LIMIT} 각주가 같은 사실을 이미 적고 있어 한 파일 안에서 두 문단이 어긋나 있었다.
   *
   * <p><b>그 오해가 위험했던 이유는 재검토 신호가 영영 안 켜지기 때문이다</b> — 「한 방에 트래픽이 몰리는 날」을 기다리는데 실제 유실은 방과 무관하게 일어난다.
   *
   * <p><b>그래서 값을 커넥션 풀에서 끌어온다.</b> 동시에 열려 있는 INSERT 수는 DB 커넥션 수를 넘을 수 없다 — 추측이 아니라 계산되는 상한이다.
   *
   * <pre>
   * HikariCP maximum-pool-size  기본 10   (운영에 별도 설정이 없다)
   * 인스턴스                      2대      (blue-green)
   *                             ─────────
   * 동시 INSERT 상한              20
   * </pre>
   *
   * <p><b>여유를 둬 그 두 배 반으로 잡는다.</b> 20 을 그대로 쓰면 인스턴스를 하나 늘리거나 풀을 16 으로 올리는 순간 조용히 깨지는데, 그 변경을 하는 사람이
   * 이 상수를 떠올릴 이유가 없다.
   *
   * <p><b>여기를 고쳐야 하는 날</b> — 인스턴스가 5대를 넘거나 {@code maximum-pool-size} 를 25 이상으로 올릴 때다. 그때는 이 값을 올리는
   * 대신 <b>커밋 시점에 매기는 별도 순번</b>(시퀀스 표 · 아웃박스)으로 가는 것이 맞다. 지금 그것을 안 치르는 이유는 위 계산이 아직 여유롭기 때문이다.
   *
   * <p><b>이 값이 {@link #LIMIT} 을 잡아먹지 않는다.</b> 읽기 상한을 둘의 합으로 두고 넘침 판정은 재연결 지점 뒤의 건수로만 한다 ({@link
   * #readSince}).
   */
  static final int BACKTRACK = 50;

  /**
   * 한 번에 되돌려줄 수 있는 최대 건수.
   *
   * <p>무한이면 <b>며칠 끊겼던 클라이언트 하나가 수만 건을 끌어간다</b> — 그 한 명의 재연결이 인스턴스의 메모리와 선로를 먹는다. 넘치면 재전송 대신 알린다
   * ({@link MissedMessages#tooMany}).
   *
   * <p><b>이 값은 「못 받은 건수」의 상한이고 되돌아간 구간은 여기 안 센다</b> (PR #142 리뷰). 한때 읽기 상한을 이 값 하나로 두어 {@link
   * #BACKTRACK} 구간의 행이 먼저 예산을 먹었고, 그래서 실효 한도가 적힌 숫자보다 작았다.
   *
   * <p>{@code id} 는 방마다 따로 매겨지지 않는다 — 표 전체에서 하나의 수열이라 다른 방이 바쁘면 {@code BACKTRACK} 만큼 물러서도 이 방의 행은
   * 그보다 적다. <b>되돌아간 구간의 실제 행 수를 예측할 수 없으니 예산에서 빼는 것이 맞다.</b>
   */
  static final int LIMIT = 100;

  private final ChatMessageRepository chatMessageRepository;
  private final Clock clock;

  /**
   * 그 방에서 {@code lastEventId} 뒤에 생긴 것을 오래된 것부터 읽는다.
   *
   * <p><b>{@code BACKTRACK + LIMIT + 1} 건을 읽고 넘침은 재연결 지점 <i>뒤</i>의 건수로 판정한다</b> (PR #142 리뷰). 읽기 상한
   * 하나로 두 가지를 재면 되돌아간 구간이 재전송 예산을 먹어 {@link #LIMIT} 이 적힌 값대로 동작하지 않는다. 한 건을 더 읽어 COUNT 없이 판정하는 수법은
   * 목록 조회와 같다 (CH-09).
   *
   * <p><b>되돌아간 구간은 넘쳐도 자르지 않는다.</b> 그쪽은 중복이라 몇 건이 오든 클라이언트가 거른다 — 예산을 지켜야 하는 것은 <b>진짜 못 받은 것</b>
   * 뿐이다.
   *
   * <p>사건으로 바꾸는 일은 {@code AuthoredMessage#toEvent} 가 한다 — 팬아웃과 같은 자리다.
   */
  @Transactional(readOnly = true)
  public MissedMessages readSince(Long roomId, Long lastEventId) {
    long from = Math.max(0L, lastEventId - BACKTRACK);

    List<AuthoredMessage> read =
        chatMessageRepository.findAfter(roomId, from, BACKTRACK + LIMIT + 1);

    long missed = read.stream().filter(message -> message.messageId() > lastEventId).count();
    if (missed > LIMIT) {
      return MissedMessages.tooMany();
    }

    return MissedMessages.of(read.stream().map(message -> message.toEvent(clock)).toList());
  }
}
