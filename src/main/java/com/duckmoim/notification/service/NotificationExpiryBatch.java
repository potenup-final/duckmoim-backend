package com.duckmoim.notification.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 30일이 지난 알림을 주기적으로 지운다 (NT-11a).
 *
 * <p><b>엔드포인트가 없다.</b> PO-14 마감 배치와 같은 자리다 — API-설계.md 「5. 결정 사항」이 <i>"후자는 서버 스케줄러다"</i> 로 정했다.
 *
 * <p><b>{@code @Scheduled} 를 service 에 둔다.</b> 아키텍처 컨벤션이 presentation 을 HTTP 관심사로 닫아 스케줄러가 갈 자리가
 * 없는데, 네 레이어 밖에 패키지를 새로 만들면 {@code LAYER_DEPENDENCY} 가 그 클래스를 아예 검사하지 않는다 ({@code
 * MeetTimePassedCloseBatch} 와 같은 판단이다).
 *
 * <p><b>잠그지 않는다.</b> 인스턴스가 둘이라 이 배치도 둘이 도는데, 삭제가 멱등이라 중복이 해가 없다 (ADR 0009).
 */
@Service
@Slf4j
public class NotificationExpiryBatch {

  /**
   * 한 주기가 돌릴 최대 청크 수.
   *
   * <p>없어도 끝난다 — 경계 시각을 처음에 한 번 읽어 고정하므로 대상 집합이 늘지 않고, 청크마다 그만큼이 지워져 다음 조회에서 빠진다. <b>그럼에도 두는 것은 그
   * 전제가 깨진 날을 위해서다.</b> 조회 조건과 삭제가 어긋나면 같은 행을 무한히 다시 집는데, 그때 상한이 없으면 배치 스레드가 영구히 물린다.
   */
  private static final int MAX_CHUNKS = 100;

  /**
   * 주기를 읽는 시간대.
   *
   * <p><b>안 주면 JVM 기본을 쓰고, 그 값이 컨테이너에서 UTC 다</b> — {@code Dockerfile} 의 {@code eclipse-temurin} 에
   * {@code TZ} 설정이 없다. 그러면 {@code 0 0 4 * * *} 가 <b>한국 시각 오후 1시</b>에 돌아, 「알림 생성이 가장 적은 새벽 4시」라는
   * {@code application.yml} 의 근거와 정반대가 된다.
   *
   * <p><b>이 저장소의 첫 벽시계 cron 이라 처음 걸리는 자리다.</b> 기존 셋은 전부 주기형이라 (10초 · 1분 · 5분) 시간대가 결과를 바꾸지 않았다 —
   * 그래서 아무도 {@code zone} 을 쓴 적이 없다.
   *
   * <p>판정 시각({@code nowInUtc})은 이것과 무관하다. 그쪽은 {@code Clock} 이 주는 절대 시각이라 시간대를 타지 않는다 — <b>여기가 정하는
   * 것은 언제 도느냐 하나다.</b>
   */
  private static final String KST = "Asia/Seoul";

  private final NotificationExpiryService notificationExpiryService;
  private final Clock clock;

  /** 알림을 얼마나 보관하는가. 개인정보 처리방침 제3조가 고지한 값이다. */
  private final Duration retention;

  /** 한 트랜잭션이 지울 최대 건수. 프로퍼티인 것은 이 반복이 몇 건짜리 검사로 증명되어야 하기 때문이다. */
  private final int chunk;

  public NotificationExpiryBatch(
      NotificationExpiryService notificationExpiryService,
      Clock clock,
      @Value("${duckmoim.notification.expiry.retention}") Duration retention,
      @Value("${duckmoim.notification.expiry.chunk}") int chunk) {

    this.notificationExpiryService = notificationExpiryService;
    this.clock = clock;
    this.retention = retention;
    this.chunk = chunk;
  }

  /**
   * 만료된 알림이 없어질 때까지 청크를 돌린다.
   *
   * <p><b>예외를 밖으로 던지지 않는다.</b> 스케줄러에서 예외가 올라가면 다음 실행이 오는지가 설정에 달리는데, 여기서 잡아 남기면 다음 주기가 곧 재시도가 된다 —
   * 삭제가 멱등이라 재시도가 안전하다.
   *
   * <p><b>0건이어도 남긴다.</b> 발송 워커가 0건일 때 로그를 아끼는 것과 반대다 ({@code application.yml} 의 워커 주석). 그쪽에서 알아야 하는
   * 것은 「무엇을 보냈나」인데 <b>여기서 알아야 하는 것은 「돌긴 했나」</b>이기 때문이다 — 이 배치가 조용히 멈추면 개인정보 처리방침 제3조가 고지한 보유 기간이
   * 깨지고, 지운 것이 없다는 로그가 없으면 멈춘 것과 구별되지 않는다. 주기가 하루에 한 번이라 값도 싸다.
   */
  @Scheduled(cron = "${duckmoim.notification.expiry.cron}", zone = KST)
  public void deleteExpiredNotifications() {
    try {
      LocalDateTime cutoffInUtc = nowInUtc().minus(retention);
      int deleted = deleteUntilDrained(cutoffInUtc);

      log.info(
          "[NotificationExpiryBatch.deleteExpiredNotifications] Expired notifications deleted."
              + " count={}, cutoff={}",
          deleted,
          cutoffInUtc);
    } catch (Exception exception) {
      log.error(
          "[NotificationExpiryBatch.deleteExpiredNotifications]"
              + " Failed to delete expired notifications.",
          exception);
    }
  }

  private int deleteUntilDrained(LocalDateTime cutoffInUtc) {
    int deleted = 0;

    for (int chunks = 0; chunks < MAX_CHUNKS; chunks++) {
      NotificationExpiry expiry = notificationExpiryService.deleteChunk(cutoffInUtc, chunk);
      deleted += expiry.deleted();

      // 집은 수로 끊는다. 지운 수로 끊으면 남이 먼저 지운 주기에 아직 남은 만료 알림을
      // 두고 배치가 끝난다
      if (expiry.picked() < chunk) {
        return deleted;
      }
    }

    log.warn(
        "[NotificationExpiryBatch.deleteUntilDrained] Chunk limit reached. deleted={}", deleted);

    return deleted;
  }

  /**
   * UTC 기준 현재 시각.
   *
   * <p><b>{@code LocalDateTime.now(clock)} 이 아니다.</b> {@code ClockConfig} 의 시계가 {@code Asia/Seoul}
   * 이라 그것을 넣으면 UTC 로 저장된 {@code created_at} 과 아홉 시간 어긋나서 <b>아직 30일이 안 된 알림을 지운다.</b> {@code
   * MeetTimePassedCloseBatch} 가 같은 자리에서 같은 변환을 쓴다.
   */
  private LocalDateTime nowInUtc() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
