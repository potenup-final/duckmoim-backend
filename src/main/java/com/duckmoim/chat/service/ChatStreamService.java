package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.MessageEvent;
import com.duckmoim.chat.infra.ChatFanout;
import com.duckmoim.chat.infra.ChatFanoutCodec;
import com.duckmoim.chat.infra.ChatFanoutEvent;
import com.duckmoim.chat.infra.ChatFanoutSubscription;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 방 단위 실시간 수신 (CH-10).
 *
 * <p><b>이 인스턴스에 붙은 연결만 들고 있다.</b> 다른 인스턴스에 붙은 사람에게 닿는 길은 {@link ChatFanout} 하나이고, 그것이 검증 기준 「다른
 * 인스턴스에 붙은 멤버의 메시지도 도착한다」가 가리키는 문제다.
 *
 * <pre>
 * 하늘 ─POST─▶ EC2-blue ──저장──▶ RDS
 *                  └─ publish ──▶ Redis chat:room:3
 *                                    ├──▶ blue  구독 핸들러 → 하늘의 연결
 *                                    └──▶ green 구독 핸들러 → 지민의 연결
 * </pre>
 *
 * <p><b>구독은 방마다 하나이고 첫 연결에 열려 마지막 연결에 닫힌다.</b> 연결마다 구독하면 같은 방의 사건이 연결 수만큼 중복으로 오고, 반대로 한 번 열고 안 닫으면
 * 아무도 안 보는 방의 채널을 계속 듣는다.
 *
 * <p><b>{@code SseEmitter} 를 알지 않는다.</b> {@link ChatStreamSession} 이 그 타입을 가린다 — {@code ChatFanout}
 * 이 {@code RedisTemplate} 을 가린 것과 같은 배치이고, 덕분에 이 클래스가 톰캣 없이 검사된다.
 *
 * <p><b>연결 목록은 이 JVM 의 메모리다.</b> 재기동하면 사라지고 그것이 맞다 — 열려 있던 TCP 연결도 함께 죽기 때문이다. <b>끊긴 뒤를 잇는 것은
 * {@link #replay} 다</b> (CH-11) — 클라이언트가 마지막으로 받은 번호를 들고 다시 붙으면 그 뒤를 되돌려준다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

  private final ChatRoomMembershipReader chatRoomReader;
  private final ChatFanout chatFanout;
  private final ChatFanoutCodec chatFanoutCodec;
  private final ChatStreamHeartbeatExecutor heartbeatExecutor;
  private final ChatStreamReplayReader replayReader;

  /**
   * 방마다 열려 있는 연결들.
   *
   * <p><b>{@code CopyOnWriteArrayList} 인 이유</b> — 순회(밀기)가 잦고 변경(연결·해제)이 드물다. 순회 중에 목록이 바뀌어도 {@code
   * ConcurrentModificationException} 이 나지 않아야 하는데, 미는 도중에 연결이 끊기는 일이 정상 경로에 있다.
   */
  private final Map<Long, List<RoomConnection>> connections = new ConcurrentHashMap<>();

  /** 방마다 하나인 Redis 구독. 첫 연결에 열고 마지막 연결에 닫는다. */
  private final Map<Long, ChatFanoutSubscription> subscriptions = new ConcurrentHashMap<>();

  /**
   * 그 방의 실시간 수신을 시작한다 (CH-10).
   *
   * <p><b>멤버 판정을 여기서 한다.</b> {@code I-18}(방 멤버가 아닌 사람은 메시지 본문에 도달할 수 없다)의 검증 위치가 「조회 조립 단일화」이고,
   * 스트림도 본문이 나가는 경로다. 나간 사람이 막히는 것도 같은 줄이다 (CH-18) — {@code isMember} 가 {@code leftAt} 이 찬 행을 멤버로
   * 세지 않는다.
   *
   * <p><b>연결한 뒤에 나가는 경우는 여기서 못 막는다.</b> 이 판정은 붙는 순간 한 번이고 연결은 몇 시간 열려 있다. 그래서 퇴장이 {@link
   * #disconnect} 로 직접 끊는다.
   *
   * <p><b>같은 모양의 구멍이 제재에도 있다</b> (STAR-84). {@code SanctionGateConfig} 가 {@code
   * /api/v1/chat-rooms/**} 를 <b>읽기까지</b> 막게 되면서 {@code BANNED} 는 스트림을 열 수 없는데, <b>이미 열어 둔 연결은 그대로
   * 흐른다</b> — 관문은 요청이 올 때만 돌고 스트림은 요청이 한 번뿐이기 때문이다.
   *
   * <pre>
   * 20:00  하늘이 스트림을 연다 (정상 회원)
   * 20:30  운영이 하늘을 BANNED 로 제재한다
   * 20:31  대화가 계속 흘러간다        ← 관문이 다시 돌 일이 없다
   * </pre>
   *
   * <p><b>이 티켓에서 막지 않았다.</b> 퇴장은 채팅 안의 명령이라 {@code ChatRoomLeaveService} 가 부르면 됐지만, 제재 실행은 Safety 의
   * 명령이다 — 채팅 티켓이 남의 도메인에 손을 넣어 연결을 끊는 것은 범위를 넘는다. <b>막으려면 제재 실행이 {@link #disconnect} 를 부르거나, 하트비트가
   * 30초마다 멤버·제재를 다시 보면 된다</b> (후자는 연결 수만큼 조회가 는다).
   *
   * <p>노출은 스트림 타임아웃 30분으로 상한이 있다 — 그 뒤 재연결할 때 관문이 다시 돌아 막힌다.
   *
   * <p><b>등록한 뒤로는 던지지 않아야 한다</b> (PR #142 리뷰). 컨트롤러는 <b>이 메서드가 돌려준 손잡이</b>로 {@code onCompletion} ·
   * {@code onTimeout} · {@code onError} 를 거는데, 등록 뒤에 예외가 나가면 그 셋 중 아무것도 안 걸린다 — 연결은 목록에 남고 그것을 뺄 길이
   * 없어진다.
   *
   * <pre>
   * connections.add(connection)   ① 넣는다
   * subscribeIfFirst / replay     ② 여기서 던지면
   * return () -> remove(...)      ③ 빼는 손잡이가 안 나온다
   * </pre>
   *
   * <p><b>피해가 좀비 하나로 끝나지 않는다.</b> {@link #unsubscribeIfEmpty} 가 「연결이 없을 때만」 구독을 닫으므로, 남은 좀비 때문에 그
   * 방의 Redis 구독이 <b>재기동까지 안 닫힌다.</b> 아무도 안 보는 방의 메시지를 계속 받아 죽은 세션에 밀어 넣고, 실패한 열기 하나당 하나씩 쌓인다 — 밀기
   * 실패는 세션이 {@code debug} 로 삼켜 로그에도 안 보인다.
   *
   * <p>그래서 등록 뒤의 두 줄을 각자 다루었다 — 구독 실패는 정리하고 다시 던지고 ({@code subscribeIfFirst} 가 실패하면 실시간이 아예 안 되므로
   * 열어 두는 것이 더 나쁘다), <b>재전송 실패는 {@link #replay} 가 삼킨다</b> (그쪽은 없어도 실시간이 돈다).
   *
   * @param lastEventId 클라이언트가 마지막으로 받은 메시지 번호. 첫 연결이면 {@code null} 이다 (CH-11)
   * @return 연결이 끝났을 때 부를 정리 작업. 부르지 않으면 죽은 연결이 방마다 쌓인다
   */
  public Runnable open(Long roomId, Long userId, ChatStreamSession session, Long lastEventId) {
    requireMember(roomId, userId);

    RoomConnection connection = new RoomConnection(userId, session);
    connections.computeIfAbsent(roomId, room -> new CopyOnWriteArrayList<>()).add(connection);
    Runnable release = () -> remove(roomId, connection);

    try {
      subscribeIfFirst(roomId);
    } catch (RuntimeException e) {
      // 등록한 뒤에 던지면 컨트롤러가 이 손잡이를 못 받아 연결이 영영 목록에 남는다.
      release.run();
      throw e;
    }

    try {
      // 구독을 건 뒤에 읽는다. 순서가 뒤집히면 그 사이 발행된 것이 사라진다 — replay() 자바독.
      replay(roomId, session, lastEventId);
    } finally {
      // 재전송이 어떻게 끝나든 담아 둔 실시간을 흘려보낸다. 안 부르면 이 연결이 영원히 조용하다.
      connection.startDelivering();
    }

    return release;
  }

  /**
   * 끊겨 있던 동안 못 받은 것을 되돌려준다 (CH-11).
   *
   * <p>검증 기준이 <b>「끊고 그 사이 N건을 보낸 뒤 재연결 → 유실 0건」</b>이다.
   *
   * <p><b>구독을 건 뒤에 읽는 것이 이 기능의 본체다.</b> 순서를 뒤집으면 읽기와 구독 사이에 발행된 것이 어느 쪽으로도 오지 않는다.
   *
   * <pre>
   * ❌ 읽고 나서 구독   DB 읽기 ──────▶ 구독 시작
   *                          ▲  이 사이에 발행된 것이 사라진다
   *
   * ✅ 구독하고 나서 읽기 구독 시작 ──────▶ DB 읽기
   *                          ▲  이 사이 것이 중복으로 온다
   * </pre>
   *
   * <p><b>유실은 못 되돌리고 중복은 되돌린다.</b> 클라이언트가 이미 {@code clientMessageId} 로 멱등 처리를 하고 있어 {@code
   * messageId} 중복 제거가 새 규칙이 아니다.
   *
   * <p><b>그 대가로 재전송과 실시간이 순서를 섞을 뻔했다.</b> 재전송은 요청 스레드이고 실시간은 구독 스레드라 읽는 동안 도착한 새 메시지가 먼저 실린다. 처음에는
   * 「클라이언트가 {@code messageId} 로 정렬하면 된다」로 적었는데 <b>그것이 틀렸다</b> (PR #142 리뷰) — 말풍선 순서는 그렇게 풀리지만
   * <b>브라우저의 책갈피({@code Last-Event-ID})는 클라이언트가 정하는 값이 아니다.</b> 선로에서 읽은 마지막 {@code id} 가 그대로 책갈피가
   * 된다.
   *
   * <p>그래서 <b>재전송이 끝날 때까지 실시간을 담아 둔다</b> — {@code RoomConnection#deliver} 와 {@code
   * RoomConnection#startDelivering} 이다. 나가는 {@code id} 가 단조 증가한다.
   *
   * <p><b>선로가 섞이지 않는 것은 {@code SseEmitter} 자신의 락이 보장한다</b> (바이트코드 확인). 여기서 다루는 것은 섞임이 아니라
   * <b>순서</b>다.
   *
   * <p><b>첫 연결에는 아무것도 하지 않는다.</b> 그때는 목록 API(CH-09)가 화면을 채운다 — 여기서까지 과거를 밀면 같은 것이 두 경로로 온다.
   *
   * <p><b>실패하면 던지지 않고 따라잡기로 넘긴다</b> (PR #142 리뷰). 이 읽기는 트랜잭션과 DB 조회라 풀 고갈 · 타임아웃 · 락 대기가 전부 {@code
   * RuntimeException} 으로 올라오는데, 여기서 나가면 {@link #open} 이 손잡이를 못 돌려줘 <b>연결과 구독이 통째로 남는다.</b>
   *
   * <p><b>대응이 상한 초과와 같다</b> — {@code sendGap} 이다. 클라이언트에 새 규칙이 생기지 않고, DB 가 한 번 삐끗한 것이 「재전송만 건너뜀,
   * 실시간은 유지」로 끝난다. 조용히 넘기지 않는 것은 <b>못 받은 구간이 있다는 사실</b>은 알려야 하기 때문이다.
   */
  private void replay(Long roomId, ChatStreamSession session, Long lastEventId) {
    if (lastEventId == null) {
      return;
    }

    try {
      MissedMessages missed = replayReader.readSince(roomId, lastEventId);
      if (missed.truncated()) {
        session.sendGap(lastEventId);
        return;
      }

      missed.events().forEach(session::send);
    } catch (RuntimeException e) {
      // 본문을 로그에 남기지 않는다. 어느 방이었는지와 무엇이 터졌는지만 남긴다.
      log.warn(
          "[ChatStreamService.replay] 재전송 실패 — 따라잡기로 넘긴다. roomId={} cause={}",
          roomId,
          e.getClass().getSimpleName());
      session.sendGap(lastEventId);
    }
  }

  /**
   * 그 사람의 연결을 이 방에서 끊는다 (CH-04 · CH-18).
   *
   * <p><b>퇴장이 이 메서드를 부른다.</b> 안 부르면 나간 사람의 연결이 살아 있어 대화가 계속 흘러간다 — 목록 조회는 매 요청 멤버를 보지만 스트림은 붙는 순간 한
   * 번만 보기 때문이다.
   *
   * <p><b>미는 시점마다 멤버를 다시 보는 방법도 있었다.</b> 그쪽은 퇴장이 스트림을 몰라도 되는 대신 <b>메시지마다 방을 조회</b>한다. 퇴장은 드물고 전송은
   * 잦으니 드문 쪽에 비용을 두었다.
   *
   * <p>연결이 없으면 아무 일도 하지 않는다 — 스트림을 안 열어 둔 채로 나가는 것이 정상이다.
   */
  public void disconnect(Long roomId, Long userId) {
    disconnectHere(roomId, userId);
    announceLeft(roomId, userId);
  }

  /**
   * 퇴장을 다른 인스턴스에도 알린다 (PR #138 리뷰).
   *
   * <p><b>연결과 퇴장 요청이 다른 인스턴스에 닿을 수 있다.</b> 리스너를 바꾸는 것은 <b>새 요청</b>의 방향뿐이라, 이미 맺어진 SSE 연결은 전환된 뒤에도 옛
   * 인스턴스에 그대로 살아 있다.
   *
   * <pre>
   * 20:00  지민 SSE 연결        ALB ▶ blue    blue.connections{3: [지민]}
   * 20:30  배포 (리스너 전환)     ALB ▶ green   blue 의 연결은 안 죽는다
   * 20:31  지민 POST /leave  ──────▶ green   green.connections{} ← 끊을 것이 없다
   * 20:32  하늘의 메시지 ─ Redis ─▶ blue    ⚠️ 나간 지민에게 계속 흘러간다
   * </pre>
   *
   * <p>노출은 스트림 타임아웃 30분까지다. 목록 조회는 매 요청 멤버를 보므로 같은 구멍이 없고, <b>스트림만 붙는 순간 한 번 보기 때문에</b> 생긴다 —
   * {@code I-18} 이 걸린 자리다.
   *
   * <p><b>이미 있는 통로에 얹는다.</b> 메시지가 다니는 그 채널로 「누가 나갔다」를 함께 보낸다. 채널을 따로 파면 방마다 구독이 둘이 되고 그 둘의 수명을 따로
   * 관리해야 한다.
   *
   * <p><b>미는 시점마다 멤버를 다시 보는 방법은 여전히 안 쓴다.</b> 그쪽은 <b>메시지마다</b> 방을 조회하는데, 이 방식은 <b>퇴장마다</b> 한 번 발행한다
   * — 「퇴장은 드물고 전송은 잦다」는 STAR-113 의 판단이 그대로 유지된다.
   *
   * <p><b>여기서도 끊고 발행도 한다.</b> 발행만 하면 Redis 가 죽었을 때 <b>자기 인스턴스의 연결조차</b> 안 끊긴다 — 지금 되는 것이 안 되게 만드는
   * 거래는 하지 않는다. 자기 발행이 Redis 를 돌아 다시 와도 그때는 끊을 연결이 없어 아무 일도 하지 않는다.
   */
  private void announceLeft(Long roomId, Long userId) {
    String payload = chatFanoutCodec.encodeMemberLeft(userId);
    if (payload == null) {
      return;
    }

    chatFanout.publish(roomId, payload);
  }

  /** 이 인스턴스에 열려 있는 그 사람의 연결만 끊는다. 없으면 아무 일도 하지 않는다. */
  private void disconnectHere(Long roomId, Long userId) {
    List<RoomConnection> room = connections.get(roomId);
    if (room == null) {
      return;
    }

    room.stream()
        .filter(connection -> connection.userId().equals(userId))
        .forEach(
            connection -> {
              connection.session().close();
              remove(roomId, connection);
            });
  }

  /**
   * 열려 있는 모든 연결에 살아 있다는 신호를 보낸다 (CH-10).
   *
   * <p><b>주기는 {@code ChatStreamHeartbeat} 가 진다.</b> 이 클래스는 트랜잭션 경계도 주기도 갖지 않는다 — {@code
   * NotificationDispatchBatch} / {@code NotificationDispatchService} 와 같은 배치다.
   *
   * <p>신호를 어떤 모양으로 보낼지는 세션이 정한다. SSE 에서는 주석 한 줄이라 화면에 아무 일도 일어나지 않는다.
   *
   * <p><b>연결마다 병렬로 보낸다</b> (PR 리뷰). 한 스레드에서 순서대로 돌면 <b>정체된 연결 하나가 나머지 전부를 막는다</b> — 모바일은 화면이 잠기거나
   * 지하철에 들어가면 소켓이 죽지 않고 송신 버퍼만 차고, 그때 {@code beat()} 가 소켓 타임아웃(수십 초)까지 반환하지 않는다.
   *
   * <pre>
   * 정체 1개  →  한 바퀴 30초 + 다음 주기 30초  =  최대 90초
   * 정체 2개  →                                     최대 150초
   *                      ▲  ALB 유휴 타임아웃은 60초
   * </pre>
   *
   * <p>그러면 <b>조용한 방의 멀쩡한 연결이 60초마다 끊기고 재연결한다</b> — 하트비트를 넣은 이유가 그대로 무너진다. 이 실패는 예외가 아니라 「반환하지 않는
   * 것」이라 {@code catch} 로도 안 잡히고 로그에도 안 남는다.
   */
  public void heartbeat() {
    connections
        .values()
        .forEach(room -> room.forEach(connection -> heartbeatExecutor.beat(connection.session())));
  }

  /** 이 방에 열려 있는 연결 수. 구독 수명과 퇴장 끊기를 검사할 때 쓴다. */
  public int connectionCount(Long roomId) {
    return connections.getOrDefault(roomId, List.of()).size();
  }

  /**
   * 첫 연결에서만 Redis 를 구독한다.
   *
   * <p>{@code computeIfAbsent} 가 같은 키에 대해 한 번만 도는 것을 이용한다 — 같은 방에 두 사람이 동시에 붙어도 구독은 하나다.
   */
  private void subscribeIfFirst(Long roomId) {
    subscriptions.computeIfAbsent(
        roomId, room -> chatFanout.subscribe(room, payload -> dispatch(room, payload)));
  }

  /**
   * Redis 에서 받은 것을 이 인스턴스의 연결들에 민다.
   *
   * <p><b>Redis 의 구독 스레드에서 불린다.</b> 그래서 여기서 예외가 나가면 그 스레드가 다음 사건을 못 받는다 — 판독 실패는 {@code null} 로
   * 돌아오고({@code ChatFanoutCodec}) 밀기 실패는 세션이 삼킨다({@link ChatStreamSession}).
   *
   * <p><b>종류를 갈라야 한다.</b> 이 통로에는 새 메시지와 퇴장 둘이 흐른다 ({@code ChatFanoutEvent}). 퇴장이 오면 이 인스턴스에 열려 있는 그
   * 사람의 연결을 끊는다 — <b>다른 인스턴스에서 나간 사람을 여기서 끊는 유일한 길</b>이다 ({@link #announceLeft}).
   *
   * <p><b>보낸 사람에게도 간다.</b> 자기 화면에는 이미 전송 응답으로 말풍선이 붙어 있지만, 그 둘은 {@code messageId} 가 같아 클라이언트가 겹치는
   * 것을 걸러낸다 — 오히려 보내는 쪽만 다르게 다루면 규칙이 하나 더 생긴다.
   */
  private void dispatch(Long roomId, String payload) {
    ChatFanoutEvent event = chatFanoutCodec.decode(payload);
    if (event == null) {
      return;
    }

    // 퇴장이 먼저다. 종류를 안 가르면 나간 사람에게 계속 흘러간다 (PR #138 리뷰).
    if (event.isMemberLeft()) {
      disconnectHere(roomId, event.leftUserId());
      return;
    }

    if (event.isMessage()) {
      push(roomId, event.message());
    }
  }

  /**
   * 이 인스턴스의 연결들에 민다.
   *
   * <p><b>연결마다 {@code deliver} 를 지난다</b> (PR #142 리뷰). 재전송 중인 연결은 그 안에서 담아 두었다가 재전송이 끝난 뒤에 받는다 —
   * 나가는 {@code id} 가 단조 증가해야 브라우저의 책갈피가 뒤로 밀리지 않는다 ({@code RoomConnection} 자바독).
   */
  private void push(Long roomId, MessageEvent message) {
    connections.getOrDefault(roomId, List.of()).forEach(connection -> connection.deliver(message));
  }

  /** 연결 하나를 목록에서 빼고, 그 방의 마지막이었으면 구독도 닫는다. */
  private void remove(Long roomId, RoomConnection connection) {
    List<RoomConnection> room = connections.get(roomId);
    if (room == null) {
      return;
    }

    room.remove(connection);

    // 비었을 때만 지운다. 지우는 사이에 새 연결이 붙으면 computeIfPresent 가 그 목록을 살려 둔다.
    connections.computeIfPresent(
        roomId, (key, remaining) -> remaining.isEmpty() ? null : remaining);
    unsubscribeIfEmpty(roomId);
  }

  private void unsubscribeIfEmpty(Long roomId) {
    if (connections.containsKey(roomId)) {
      return;
    }

    ChatFanoutSubscription subscription = subscriptions.remove(roomId);
    if (subscription != null) {
      subscription.close();
    }
  }

  /**
   * 방이 있고 요청자가 그 방의 멤버인가. 목록 조회·삭제와 같은 두 코드를 쓴다.
   *
   * <p><b>판정만 별 빈에 있다</b> (PR 리뷰). {@code @Transactional} 을 {@link #open} 에 걸면 그 메서드가 끝날 때까지 DB
   * 커넥션을 쥐는데, 그 안에 Redis 구독이 들어 있다 — 그리고 {@code RedisMessageListenerContainer} 는 구독 등록을 <b>기본 2초까지
   * 기다린다</b> (바이트코드로 확인: {@code maxSubscriptionRegistrationWaitingTime = 2000L}).
   *
   * <pre>
   * Redis 장애
   *    스트림 열기 10개 × DB 커넥션 2초 점유
   *       → HikariCP 기본 풀 10 고갈
   *          → 로그인·모집글·댓글·알림까지 전부 커넥션 대기
   * </pre>
   *
   * <p><b>{@code ChatFanout} 이 자바독에 약속한 것과 정반대다</b> — <i>"이 포트의 장애가 메시지 전송의 500 이 되면 부가 기능의 장애가 본
   * 기능을 죽이는 구조가 된다"</i>. 발행은 그 계약을 지키는데 구독 경로가 트랜잭션 안이라 Redis 장애가 DB 로 번졌다.
   *
   * <p>자기 클래스의 메서드를 부르면 프록시를 지나지 않아 {@code @Transactional} 이 안 걸린다. 그래서 빈을 나눈다 — {@code
   * ChatMessageSendService} / {@code ChatMessageWriter} 와 같은 배치다.
   */
  private void requireMember(Long roomId, Long userId) {
    chatRoomReader.requireMember(roomId, userId);
  }

  /**
   * 연결 하나. <b>재전송이 끝날 때까지 실시간을 붙들고 있는다</b> (PR #142 리뷰).
   *
   * <p>누가 붙어 있는지를 함께 드는 것은 퇴장이 그 사람의 것만 끊기 위해서다.
   *
   * <p><b>버퍼가 필요한 이유는 브라우저의 책갈피가 우리 것이 아니기 때문이다.</b> SSE 명세상 {@code EventSource} 는 <b>마지막으로 받은</b>
   * 사건의 {@code id} 를 기억한다 — 가장 큰 값이 아니다. 재전송 도중에 실시간 사건이 끼어들면 책갈피가 그 값으로 덮이고, 하필 그때 끊기면 사이 구간이 영영 안
   * 온다.
   *
   * <pre>
   * resumeFrom = 101,  재전송 대상 = 102 … 201
   *
   * 선로   102, 103, [실시간 202], 104, 105 …
   *                      ▲ 구독 스레드가 먼저 썼다
   * 여기서 끊기면  브라우저 Last-Event-ID = 202
   *    다음 재연결 → from = 202 - BACKTRACK
   *    → 104 … 사이 구간이 영영 안 온다
   * </pre>
   *
   * <p><b>「클라이언트가 messageId 로 정렬하면 된다」로 적어 두었던 것이 틀렸다.</b> 화면의 말풍선 순서는 그렇게 풀리지만 <b>책갈피는 클라이언트가 정하는
   * 값이 아니다</b> — 브라우저가 선로에서 읽은 마지막 {@code id} 를 그대로 쓴다.
   *
   * <p><b>레코드가 아니라 클래스인 것도 의도다.</b> 목록에서 뺄 때 <b>바로 그 연결</b>을 지목해야 하는데, 값 동등성이면 같은 사람이 두 번 붙었을 때 엉뚱한
   * 쪽을 지울 수 있다.
   */
  private static final class RoomConnection {

    private final Long userId;
    private final ChatStreamSession session;

    /** 재전송이 끝나기 전에 도착한 실시간 사건. 순서를 지켜야 해서 FIFO 다. */
    private final Queue<MessageEvent> buffered = new ArrayDeque<>();

    /** 열자마자 참이다. {@link #startDelivering} 이 한 번 내린다. */
    private boolean replaying = true;

    private final ReentrantLock lock = new ReentrantLock();

    private RoomConnection(Long userId, ChatStreamSession session) {
      this.userId = userId;
      this.session = session;
    }

    Long userId() {
      return userId;
    }

    ChatStreamSession session() {
      return session;
    }

    /**
     * 실시간 사건 하나를 넘긴다. <b>구독 스레드에서 불린다.</b>
     *
     * <p>재전송 중이면 담아 두고, 아니면 바로 민다. <b>미는 것은 잠금 밖이다</b> — 정체된 연결에 쓰는 동안 잠금을 쥐고 있으면 그 방의 구독 스레드가 함께
     * 묶인다. 선로가 섞이지 않는 것은 {@code SseEmitter} 자신의 락이 보장한다.
     */
    void deliver(MessageEvent event) {
      lock.lock();
      try {
        if (replaying) {
          buffered.add(event);
          return;
        }
      } finally {
        lock.unlock();
      }

      session.send(event);
    }

    /**
     * 재전송이 끝났다. 담아 둔 것을 순서대로 흘려보낸다.
     *
     * <p><b>비우는 동안 잠금을 쥔다.</b> 그래야 이 사이에 도착한 사건이 {@link #deliver} 에서 기다렸다가 큐 뒤에 실린다 — 놓으면 그 사건이 큐보다
     * 먼저 나가 다시 순서가 뒤집힌다.
     *
     * <p>두 번 불러도 안전하다. 두 번째는 큐가 비어 있어 아무 일도 하지 않는다.
     */
    void startDelivering() {
      lock.lock();
      try {
        replaying = false;
        for (MessageEvent event = buffered.poll(); event != null; event = buffered.poll()) {
          session.send(event);
        }
      } finally {
        lock.unlock();
      }
    }
  }
}
