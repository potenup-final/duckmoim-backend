package com.duckmoim.companion.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 만남시각이 지난 모집글을 주기적으로 닫는다 (PO-14).
 *
 * <p><b>엔드포인트가 없다.</b> API-설계.md 「5. 결정 사항」이 <i>"PO-13 목록 검색과 PO-14 마감 배치에는 엔드포인트가 없다. 전자는 브라우저 필터,
 * 후자는 서버 스케줄러다"</i> 로 정했다.
 *
 * <p><b>{@code @Scheduled} 를 service 에 둔다.</b> 아키텍처 컨벤션이 presentation 을 <i>"HTTP 관심사만 다룬다"</i> 로 닫아
 * 스케줄러가 갈 자리가 없는데, 네 레이어 밖에 패키지를 새로 만들면 {@code LAYER_DEPENDENCY} 가 그 클래스의 service 참조를 <b>아예 검사하지
 * 않는다</b> ({@code consideringOnlyDependenciesInLayers}). 통과하는 것과 규칙을 지키는 것은 다르고, 그 패키지가 선례가 되면 다음
 * 사람이 저장소도 거기서 부른다.
 *
 * <p><b>{@link MeetTimePassedCloseService} 와 빈을 나눈 이유는 트랜잭션이다.</b> 청크 하나가 트랜잭션 하나여야 하는데 같은 클래스 안에서
 * 부르면 프록시를 지나지 않아 {@code @Transactional} 이 걸리지 않는다. 그래서 여기는 트랜잭션을 열지 않고 <b>반복만</b> 진다.
 */
@Service
@Slf4j
public class MeetTimePassedCloseBatch {

  /**
   * 한 주기가 돌릴 최대 청크 수.
   *
   * <p>없어도 끝난다 — 현재 시각을 처음에 한 번 읽어 고정하므로 대상 집합이 늘지 않고, 청크마다 그만큼이 {@code CLOSED} 가 되어 다음 조회에서 빠진다.
   * <b>그럼에도 두는 것은 그 전제가 깨진 날을 위해서다.</b> 조회 조건과 도메인 판정이 어긋나면 같은 행을 무한히 다시 집는데, 그때 상한이 없으면 배치 스레드가
   * 영구히 물린다.
   */
  private static final int MAX_CHUNKS = 100;

  private final MeetTimePassedCloseService meetTimePassedCloseService;
  private final Clock clock;

  /** 한 트랜잭션이 닫을 최대 건수. 프로퍼티인 것은 이 반복이 세 건짜리 테스트로 증명되어야 하기 때문이다. */
  private final int chunk;

  public MeetTimePassedCloseBatch(
      MeetTimePassedCloseService meetTimePassedCloseService,
      Clock clock,
      @Value("${duckmoim.batch.post-close.chunk}") int chunk) {

    this.meetTimePassedCloseService = meetTimePassedCloseService;
    this.clock = clock;
    this.chunk = chunk;
  }

  /**
   * 밀린 글이 없어질 때까지 청크를 돌린다.
   *
   * <p><b>예외를 밖으로 던지지 않는다.</b> 스케줄러에서 예외가 올라가면 다음 실행이 오는지가 설정에 달리는데, 여기서 잡아 남기면 <b>다음 주기가 곧
   * 재시도</b>가 된다 — 도메인이 멱등이라 (PO-14) 재시도가 안전하다. 코드-컨벤션.md 「ERROR」가 <i>"배치 실패처럼 운영 영향이 있는 실패"</i>를 이
   * 레벨로 정했다.
   *
   * <p><b>0건일 때는 남기지 않는다.</b> 주기마다 「0건 닫음」이 쌓이면 실제로 닫힌 날을 로그에서 찾을 수 없다. 코드-컨벤션.md 「로그 레벨」이 INFO 를
   * <i>"운영 흐름상 의미 있는 주요 이벤트 — 모집글 마감"</i> 으로 정한 것과 같은 취지다.
   */
  @Scheduled(cron = "${duckmoim.batch.post-close.cron}")
  public void closeMeetTimePassedPosts() {
    try {
      int closed = closeUntilDrained(nowInUtc());

      if (closed > 0) {
        log.info(
            "[MeetTimePassedCloseBatch.closeMeetTimePassedPosts] Posts closed. count={}", closed);
      }
    } catch (Exception exception) {
      log.error(
          "[MeetTimePassedCloseBatch.closeMeetTimePassedPosts] Failed to close posts.", exception);
    }
  }

  private int closeUntilDrained(LocalDateTime nowInUtc) {
    int closed = 0;

    for (int chunks = 0; chunks < MAX_CHUNKS; chunks++) {
      int closedInChunk = meetTimePassedCloseService.closeChunk(nowInUtc, chunk);
      closed += closedInChunk;

      if (closedInChunk < chunk) {
        return closed;
      }
    }

    log.warn("[MeetTimePassedCloseBatch.closeUntilDrained] Chunk limit reached. closed={}", closed);

    return closed;
  }

  /**
   * UTC 기준 현재 시각.
   *
   * <p><b>{@code LocalDateTime.now(clock)} 이 아니다.</b> {@code ClockConfig} 의 시계가 {@code Asia/Seoul}
   * 이라 (행사 종료일 판정이 KST 여야 해서 그렇게 정해졌다) 그것을 넣으면 UTC 로 저장된 {@code meet_at} 과 아홉 시간 어긋나서 <b>아직 만나지 않은
   * 글을 전부 닫는다.</b> {@code AuditLogRecorder} 가 같은 자리에서 같은 변환을 쓴다.
   */
  private LocalDateTime nowInUtc() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
