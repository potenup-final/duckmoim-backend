package com.duckmoim.chat.service;

import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 하트비트를 연결마다 병렬로 보낸다 (CH-10 · PR 리뷰).
 *
 * <p><b>한 스레드에서 순서대로 보내면 정체된 연결 하나가 나머지 전부를 막는다.</b> 모바일은 화면이 잠기거나 지하철에 들어가면 소켓이 죽지 않고 송신 버퍼만 차고,
 * 그때 쓰기가 소켓 타임아웃(수십 초)까지 반환하지 않는다.
 *
 * <pre>
 * 정체 1개  →  한 바퀴 30초 + 다음 주기 30초  =  최대 90초
 * 정체 2개  →                                     최대 150초
 *                      ▲  ALB 유휴 타임아웃은 60초
 * </pre>
 *
 * <p>그 결과가 <b>조용한 방의 멀쩡한 연결이 60초마다 끊기는 것</b>이라, 하트비트를 넣은 이유가 그대로 무너진다.
 *
 * <p><b>배치 스케줄러({@code taskScheduler})를 빌려 쓰지 않는다.</b> 그쪽은 풀이 2인데 {@code @Scheduled} 가 일곱이다 — 여기서
 * 하나를 수십 초 잡으면 10초 주기인 알림 워커가 밀린다. 채팅의 정체가 알림을 죽이는 구조를 만들지 않는다.
 *
 * <p><b>큐가 넘치면 버린다.</b> 팬아웃과 반대 선택이다 — 메시지는 늦더라도 가야 하지만 <b>하트비트는 다음 주기에 또 온다.</b> 30초 뒤에 다시 보낼 신호를
 * 위해 부르는 쪽을 붙잡을 이유가 없다.
 */
@Slf4j
@Component
public class ChatStreamHeartbeatExecutor implements DisposableBean {

  /** 평상시 쓰기는 밀리초라 몇 개면 충분하다. */
  private static final int CORE_POOL_SIZE = 4;

  /** 상한. 정체된 연결이 늘어도 스레드가 무한히 늘지 않는다. */
  private static final int MAX_POOL_SIZE = 32;

  /** 한 주기에 담을 수 있는 연결 수. 넘치면 그 주기의 나머지는 건너뛴다. */
  private static final int QUEUE_CAPACITY = 1000;

  private static final int SHUTDOWN_WAIT_SECONDS = 5;

  private final ThreadPoolTaskExecutor executor = create();

  /** 그 연결에 신호를 보낸다. 실패도 지연도 부르는 쪽으로 새지 않는다. */
  public void beat(ChatStreamSession session) {
    executor.execute(session::beat);
  }

  @Override
  public void destroy() {
    executor.shutdown();
  }

  private static ThreadPoolTaskExecutor create() {
    ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();

    pool.setCorePoolSize(CORE_POOL_SIZE);
    pool.setMaxPoolSize(MAX_POOL_SIZE);
    pool.setQueueCapacity(QUEUE_CAPACITY);
    pool.setThreadNamePrefix("duckmoim-heartbeat-");
    // 넘치면 조용히 버린다. 다음 주기에 또 보내므로 쌓아 둘 이유가 없다.
    pool.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
    pool.setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS);
    pool.initialize();

    return pool;
  }
}
