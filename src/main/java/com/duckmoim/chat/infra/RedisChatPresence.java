package com.duckmoim.chat.infra;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Component;

/**
 * {@link ChatPresence} 를 Redis 정렬 집합으로 구현한다 (NT-07).
 *
 * <p><b>방마다 집합 하나이고 점수가 마지막으로 살아 있다고 알린 시각이다.</b> 읽을 때 신선도 기준보다 오래된 점수는 세지 않는다.
 *
 * <pre>
 * chat:room:3:viewers   {지민: 1757660430, 하늘: 1757660100}
 *                                  ▲ 30초 전            ▲ 6분 전 — 안 센다
 * </pre>
 *
 * <p><b>집합에 TTL 을 거는 방식이 아닌 이유.</b> Redis 의 만료는 <b>키 전체</b>에 걸려서 사람마다 따로 늙힐 수 없다. 점수를 시각으로 두면 한 집합
 * 안에서 각자 자기 시각으로 판정된다.
 *
 * <p><b>인스턴스가 죽어도 스스로 풀린다.</b> 아무도 갱신하지 않으면 키가 통째로 만료되고, 일부만 살아 있으면 갱신하는 김에 낡은 점수를 걷어낸다. 상태를 지워 줄
 * 주체를 따로 두지 않아도 되는 것이 이 방식의 값이다.
 *
 * <p><b>예외를 삼킨다.</b> {@link ChatPresence} 의 계약이다. 삼키되 로그는 남긴다 — 조용히 실패하면 「억제가 안 되는데 아무도 모르는」 상태가
 * 된다.
 *
 * <h2>「보고 있다」의 근거가 약하다 — 남아 있는 한계 (PR #145 리뷰)</h2>
 *
 * <p><b>여기 적히는 근거는 「연결이 아직 목록에 있다」이지 「그 사람이 화면을 보고 있다」가 아니다.</b> 폰 화면이 꺼지거나 지하철에 들어가면 TCP 가 반쯤 열린
 * 채로 남는데, 그때 keep-alive 쓰기는 송신 버퍼에 들어가 <b>성공으로 반환한다.</b> 서버는 그 연결이 죽은 줄 모르고 계속 점수를 올린다.
 *
 * <p>틀리는 방향이 나쁜 쪽이다. 안 보고 있는 사람이 「보는 중」으로 남으면 그 방에 온 메시지가 {@code ChatMessageWriter} 의 수신자에서 빠져
 * <b>알림 행이 아예 안 생기고, 나중에 메울 배치도 없다.</b> API-설계.md 「채팅 알림 (NT-07)」이 <i>"막는 쪽으로 실패하면 그 알림은 재시도 없이
 * 사라진다"</i> 고 경고한 그 자리다.
 *
 * <p><b>노출을 6분 30초로 묶어 두었다</b> — 스트림 타임아웃 5분({@code ChatMessageController}) + 신선도 90초. 타임아웃은 TCP
 * 상태와 무관하게 벽시계로 돌아 반쯤 열린 연결도 반드시 걷고, 다시 붙으려면 핸드셰이크를 새로 맺어야 하므로 <b>재연결 자체가 살아 있다는 증거</b>가 된다.
 *
 * <p><b>그 창을 없애려면 클라이언트가 찍어 줘야 한다.</b> SSE 는 단방향이라 서버 쪽에 그 사람이 살아 있다는 증거가 원래 없다 — {@code beat()} 에
 * 성공/실패를 물어봐도 소용없다. 깨끗하게 닫힌 연결은 이미 오류 콜백이 {@link #leave} 까지 부르고, 문제는 <b>쓰기가 성공하는</b> 반쯤 열린 경우다.
 *
 * <p><b>다시 볼 시점은 「알림이 안 왔다」는 문의가 들어오는 날</b>이고, 그때는 「이 방 보는 중」을 주기적으로 찍는 문을 여는 쪽으로 간다. 프론트 변경이 딸려 별도
 * 티켓이다.
 */
@Slf4j
@Component
public class RedisChatPresence implements ChatPresence {

  /** {@code chat:room:42:viewers} 가 42번 방을 보고 있는 사람들이다. 팬아웃 채널과 접두어를 맞춘다. */
  private static final String KEY_PREFIX = "chat:room:";

  private static final String KEY_SUFFIX = ":viewers";

  private final StringRedisTemplate redisTemplate;

  /**
   * 이만큼 갱신이 없으면 「보고 있지 않다」로 본다.
   *
   * <p>하트비트 주기의 배수여야 한다. 같거나 짧으면 <b>멀쩡히 보고 있는 사람이 갱신 직전마다 빠진다.</b>
   */
  private final Duration staleAfter;

  private final Clock clock;

  public RedisChatPresence(
      StringRedisTemplate redisTemplate,
      Clock clock,
      @Value("${duckmoim.chat.stream.presence-stale-after}") Duration staleAfter) {

    this.redisTemplate = redisTemplate;
    this.clock = clock;
    this.staleAfter = staleAfter;
  }

  @Override
  public void enter(long roomId, long userId) {
    refresh(roomId, Set.of(userId));
  }

  @Override
  public void leave(long roomId, long userId) {
    try {
      redisTemplate.opsForZSet().remove(keyOf(roomId), String.valueOf(userId));
    } catch (RuntimeException e) {
      // 못 지워도 신선도 기준이 뒤에서 받는다. 갱신이 멈추면 저절로 빠진다.
      log.warn(
          "[RedisChatPresence.leave] 접속 해제 기록 실패 — 신선도 기준으로 빠진다. roomId={} cause={}",
          roomId,
          e.getClass().getSimpleName());
    }
  }

  /**
   * 붙어 있는 사람들의 시각을 한 번에 올리고, 낡은 점수를 걷어낸다.
   *
   * <p><b>걷어내는 것을 읽는 쪽이 아니라 여기서 한다.</b> 읽기는 메시지 전송마다 도는 길이라 쓰기를 섞지 않는다. 갱신은 30초에 한 번이라 그 일을 얹기에
   * 알맞다.
   *
   * <p><b>명령 셋을 파이프라인 하나로 보낸다</b> (PR #145 리뷰). 따로 보내면 왕복이 셋이고 {@code spring.data.redis.timeout:
   * 1s} 가 명령마다 걸려 방 하나가 최악 3초인데, 부르는 쪽이 30초마다 방 전체를 도는 주기 작업이라 그 3초가 방 수만큼 곱해진다.
   *
   * <p><b>{@code SessionCallback} 이어야 한다. {@code RedisCallback} 은 조용히 안 묶인다.</b> 파이프라인이 성립하려면 안쪽
   * 명령들이 <b>같은 커넥션</b>을 타야 하는데, 커넥션을 스레드에 묶어 주는 것은 이 변형뿐이다 (바이트코드 확인 — {@code
   * executePipelined(SessionCallback)} 은 {@code bindConnection} 을 부르고 {@code
   * executePipelined(RedisCallback)} 은 {@code doGetConnection} 만 부른다). 뒤엣것을 쓰면 안쪽 호출이 <b>각자 새 커넥션으로
   * 즉시 실행되고 빈 파이프라인만 오간다</b> — 왕복이 셋에서 넷으로 늘 뿐 에러도 경고도 없다.
   *
   * <p>그 대가로 이 안에서는 {@code redisTemplate} 을 그대로 쓴다. 묶인 커넥션은 스레드에 걸려 있어 같은 팩터리를 쓰는 호출이 알아서 그것을 집는다.
   *
   * <p><b>콜백 안에서는 반환값을 읽지 않는다.</b> 파이프라인은 명령을 모아 두었다가 한 번에 보내므로 그 안의 {@code opsForZSet()} 호출은 전부
   * {@code null} 을 돌려준다 — 여기서 그 값을 쓰면 <b>NPE 가 되고, 그것도 이 {@code catch} 가 삼켜 조용해진다.</b> 던지기만 한다.
   *
   * <p>순서는 그대로 지켜진다. 파이프라인은 보내는 방식만 바꾸고 <b>서버가 받아 실행하는 순서는 적은 순서</b>다 — 낡은 점수를 걷어내는 것이 방금 올린 점수보다
   * 먼저 돌면 안 된다.
   */
  @Override
  public void refresh(long roomId, Set<Long> userIds) {
    if (userIds.isEmpty()) {
      return;
    }

    try {
      String key = keyOf(roomId);
      double now = nowInSeconds();

      redisTemplate.executePipelined(
          new SessionCallback<Object>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) {
              redisTemplate.opsForZSet().add(key, tuplesOf(userIds, now));
              redisTemplate.opsForZSet().removeRangeByScore(key, 0, freshSince(now));

              // 아무도 갱신하지 않으면 키가 통째로 사라진다. 갱신이 도는 동안은 계속 밀린다.
              redisTemplate.expire(key, staleAfter);

              return null;
            }
          });
    } catch (RuntimeException e) {
      log.warn(
          "[RedisChatPresence.refresh] 접속 갱신 실패 — 알림 억제만 건너뛴다. roomId={} cause={}",
          roomId,
          e.getClass().getSimpleName());
    }
  }

  @Override
  public Set<Long> viewers(long roomId) {
    try {
      // 위쪽을 열어 둔다. 인스턴스 사이에 시계가 조금 어긋나면 남이 적은 점수가 내 지금보다
      // 클 수 있는데, 상한을 지금으로 두면 보고 있는 사람이 시계 차이만큼 안 보이게 된다.
      Set<String> fresh =
          redisTemplate
              .opsForZSet()
              .rangeByScore(keyOf(roomId), freshSince(nowInSeconds()), Double.MAX_VALUE);

      return fresh == null
          ? Set.of()
          : fresh.stream().map(Long::valueOf).collect(Collectors.toSet());
    } catch (RuntimeException e) {
      // **열리는 쪽으로 실패한다.** 막는 쪽이면 그 알림은 재시도도 없이 사라진다.
      log.warn(
          "[RedisChatPresence.viewers] 접속 조회 실패 — 억제 없이 알림을 만든다. roomId={} cause={}",
          roomId,
          e.getClass().getSimpleName());

      return Set.of();
    }
  }

  private double nowInSeconds() {
    return clock.instant().getEpochSecond();
  }

  private double freshSince(double now) {
    return now - staleAfter.getSeconds();
  }

  private static Set<TypedTuple<String>> tuplesOf(Set<Long> userIds, double now) {
    return userIds.stream()
        .map(userId -> TypedTuple.of(String.valueOf(userId), now))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static String keyOf(long roomId) {
    return KEY_PREFIX + roomId + KEY_SUFFIX;
  }
}
