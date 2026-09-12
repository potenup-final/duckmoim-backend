package com.duckmoim.chat.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 메시지에 실리지 않은 사진을 주기적으로 치운다 (CH-17 · N-4).
 *
 * <p><b>엔드포인트가 없다.</b> {@code NotificationExpiryBatch} · {@code MeetTimePassedCloseBatch} 와 같은 자리다
 * — API-설계.md 「5. 결정 사항」이 이런 일을 <i>"서버 스케줄러"</i> 로 정했다.
 *
 * <p><b>{@code @Scheduled} 를 service 에 둔다.</b> 아키텍처 컨벤션이 presentation 을 HTTP 관심사로 닫아 스케줄러가 갈 자리가
 * 없고, 네 레이어 밖에 패키지를 만들면 {@code LAYER_DEPENDENCY} 가 그 클래스를 아예 검사하지 않는다.
 *
 * <p><b>잠그지 않는다.</b> 인스턴스가 둘이라 이 배치도 둘이 도는데, 삭제가 멱등이라 중복이 해가 없다 (ADR 0009) — S3 의 {@code
 * DeleteObject} 가 없는 키에도 성공하고, 행 삭제는 먼저 지운 쪽이 이긴다.
 *
 * <p><b>시간대를 박는다.</b> 안 주면 JVM 기본을 쓰고 그 값이 컨테이너에서 UTC 라 ({@code Dockerfile} 에 {@code TZ} 가 없다) 새벽에
 * 돌라고 쓴 cron 이 한국 시각 오후에 돈다 — {@code NotificationExpiryBatch} 가 이 저장소에서 처음 밟은 자리다.
 */
@Slf4j
@Service
public class ChatImageCleanupBatch {

  private static final String KST = "Asia/Seoul";

  /**
   * 한 주기가 돌릴 최대 청크 수.
   *
   * <p>없어도 끝난다 — 기준 시각을 처음에 한 번 읽어 고정하므로 대상 집합이 늘지 않는다. <b>그럼에도 두는 것은 그 전제가 깨진 날을 위해서다</b>: 저장소 삭제가
   * 계속 실패하면 같은 행을 집고 지우지 못해 <b>무한히 돈다.</b> 알림 배치는 「집었으면 지워진다」가 보장돼 이 위험이 없었다.
   */
  private static final int MAX_CHUNKS = 50;

  private final ChatImageCleanupService chatImageCleanupService;
  private final Clock clock;
  private final Duration retention;
  private final int batchSize;

  public ChatImageCleanupBatch(
      ChatImageCleanupService chatImageCleanupService,
      Clock clock,
      @Value("${duckmoim.chat.image.orphan-retention}") Duration retention,
      @Value("${duckmoim.chat.image.orphan-batch-size}") int batchSize) {

    this.chatImageCleanupService = chatImageCleanupService;
    this.clock = clock;
    this.retention = retention;
    this.batchSize = batchSize;
  }

  /**
   * 고아가 없어질 때까지 청크를 돌린다.
   *
   * <p><b>예외를 밖으로 던지지 않는다.</b> 스케줄러에서 예외가 올라가면 다음 실행이 오는지가 설정에 달린다. 여기서 잡아 남기면 다음 주기가 곧 재시도가 된다 —
   * 삭제가 멱등이라 재시도가 안전하다.
   *
   * <p><b>0건이어도 남긴다.</b> 이 배치가 조용히 멈추면 쓰다 만 사진이 저장소에 영구히 쌓이고, <b>지운 것이 없다는 로그가 없으면 멈춘 것과 구별되지
   * 않는다.</b> 주기가 하루에 한 번이라 값도 싸다.
   *
   * <p><b>집은 수와 지운 수를 함께 남긴다.</b> 둘이 갈리면 저장소 삭제가 실패하고 있다는 뜻이다 — 인스턴스 역할에 {@code s3:DeleteObject} 가
   * 없을 때의 증상이 정확히 그 모양이라, 한쪽만 찍으면 그 고장이 안 보인다.
   */
  @Scheduled(cron = "${duckmoim.chat.image.cleanup-cron}", zone = KST)
  public void deleteOrphanImages() {
    try {
      LocalDateTime thresholdInUtc = nowInUtc().minus(retention);
      ChatImageCleanup total = deleteUntilDrained(thresholdInUtc);

      log.info(
          "[ChatImageCleanupBatch.deleteOrphanImages] 고아 이미지를 치웠다."
              + " picked={}, deleted={}, threshold={}",
          total.picked(),
          total.deleted(),
          thresholdInUtc);
    } catch (Exception exception) {
      log.error("[ChatImageCleanupBatch.deleteOrphanImages] 고아 이미지 정리에 실패했다.", exception);
    }
  }

  private ChatImageCleanup deleteUntilDrained(LocalDateTime thresholdInUtc) {
    int picked = 0;
    int deleted = 0;

    for (int chunks = 0; chunks < MAX_CHUNKS; chunks++) {
      ChatImageCleanup chunk = chatImageCleanupService.deleteChunk(thresholdInUtc, batchSize);
      picked += chunk.picked();
      deleted += chunk.deleted();

      // 집은 수로 끊는다. 지운 수로 끊으면 저장소 삭제가 실패한 주기에 아직 남은 고아를
      // 두고 배치가 끝난다.
      if (chunk.picked() < batchSize) {
        return new ChatImageCleanup(picked, deleted);
      }

      // 한 청크가 가득 찼는데 하나도 못 지웠으면 저장소가 막힌 것이다. 계속 돌면 같은
      // 행을 MAX_CHUNKS 번 다시 집는다.
      if (chunk.deleted() == 0) {
        log.warn(
            "[ChatImageCleanupBatch.deleteUntilDrained] 집었으나 하나도 지우지 못했다 — 저장소 권한을 확인한다."
                + " picked={}",
            chunk.picked());
        return new ChatImageCleanup(picked, deleted);
      }
    }

    return new ChatImageCleanup(picked, deleted);
  }

  /** 저장된 {@code created_at} 이 UTC 라 기준 시각도 UTC 여야 한다 ({@code BaseEntity}). */
  private LocalDateTime nowInUtc() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
