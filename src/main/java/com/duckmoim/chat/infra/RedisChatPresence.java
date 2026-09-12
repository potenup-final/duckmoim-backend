package com.duckmoim.chat.infra;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
   */
  @Override
  public void refresh(long roomId, Set<Long> userIds) {
    if (userIds.isEmpty()) {
      return;
    }

    try {
      String key = keyOf(roomId);
      double now = nowInSeconds();

      redisTemplate.opsForZSet().add(key, tuplesOf(userIds, now));
      redisTemplate.opsForZSet().removeRangeByScore(key, 0, freshSince(now));

      // 아무도 갱신하지 않으면 키가 통째로 사라진다. 갱신이 도는 동안은 계속 밀린다.
      redisTemplate.expire(key, staleAfter);
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
