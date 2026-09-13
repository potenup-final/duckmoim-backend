package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.ChatImageStorage;
import com.duckmoim.chat.service.ClaimedChatImages.ClaimedChatImage;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 보관 기간이 지난 방의 대화와 사진을 주기적으로 파기한다 (CH-19).
 *
 * <p>명세: <b>「모집글 마감 후 90일에 대화와 이미지를 파기한다. 배치는 멱등」</b>. 개인정보 처리방침 제3조 · 제8조가 고지한 보유 기간이라, 이 배치가 조용히
 * 멈추면 공개한 약속이 깨진다.
 *
 * <p><b>엔드포인트가 없다.</b> {@code NotificationExpiryBatch} · {@code ChatImageCleanupBatch} · {@code
 * MeetTimePassedCloseBatch} 와 같은 자리다 — API-설계.md 「5. 결정 사항」이 이런 일을 <i>"서버 스케줄러"</i> 로 정했다.
 *
 * <p><b>{@code @Scheduled} 를 service 에 둔다.</b> 아키텍처 컨벤션이 presentation 을 HTTP 관심사로 닫아 스케줄러가 갈 자리가
 * 없고, 네 레이어 밖에 패키지를 만들면 {@code LAYER_DEPENDENCY} 가 그 클래스를 아예 검사하지 않는다.
 *
 * <p><b>잠그지 않는다</b> (ADR 0009). 인스턴스가 둘이라 이 배치도 둘이 도는데, 삭제가 멱등이라 중복이 해가 없다 — 저장소의 {@code
 * DeleteObject} 가 없는 키에도 성공하고, 행 삭제는 먼저 지운 쪽이 이긴다.
 *
 * <p><b>시간대를 박는다.</b> 안 주면 JVM 기본을 쓰고 그 값이 컨테이너에서 UTC 라 ({@code Dockerfile} 에 {@code TZ} 가 없다) 새벽에
 * 돌라고 쓴 cron 이 한국 시각 오후에 돈다 — {@code NotificationExpiryBatch} 가 이 저장소에서 처음 밟은 자리다.
 */
@Slf4j
@Service
public class ChatPurgeBatch {

  private static final String KST = "Asia/Seoul";

  /**
   * 한 주기가 돌릴 최대 청크 수.
   *
   * <p>없어도 끝난다 — 기준 시각을 처음에 한 번 읽어 고정하므로 대상 집합이 늘지 않고, 파기한 방은 {@code purged_at} 이 차 다음 조회에서 빠진다.
   * <b>그럼에도 두는 것은 그 전제가 깨진 날을 위해서다</b>: 저장소 삭제가 계속 실패하면 그 방이 표시되지 않아 같은 청크를 <b>무한히 다시 집는다</b>
   * ({@code ChatImageCleanupBatch} 와 같은 위험이다).
   */
  private static final int MAX_CHUNKS = 50;

  private final ChatPurgeService chatPurgeService;
  private final ChatImageStorage storage;
  private final ChatImageJobExecutor jobExecutor;
  private final Clock clock;

  /** 앞 회차가 아직 도는가. 전용 스레드로 넘기면 {@code @Scheduled} 의 「겹치지 않음」 보장이 사라져 직접 든다. */
  private final AtomicBoolean running = new AtomicBoolean();

  /** 대화와 사진을 얼마나 보관하는가. 개인정보 처리방침 제3조가 고지한 값이다. */
  private final Duration retention;

  /** 한 청크가 파기할 최대 방 수. */
  private final int batchSize;

  public ChatPurgeBatch(
      ChatPurgeService chatPurgeService,
      ChatImageStorage storage,
      ChatImageJobExecutor jobExecutor,
      Clock clock,
      @Value("${duckmoim.chat.purge.retention}") Duration retention,
      @Value("${duckmoim.chat.purge.batch-size}") int batchSize) {

    this.chatPurgeService = chatPurgeService;
    this.storage = storage;
    this.jobExecutor = jobExecutor;
    this.clock = clock;
    this.retention = retention;
    this.batchSize = batchSize;
  }

  /**
   * 전용 스레드로 넘긴다.
   *
   * <p><b>스케줄러 스레드에서 돌리지 않는다.</b> 한 회차가 최대 50청크 × 방마다의 저장소 삭제라, 배치 스케줄러(스레드 2)의 한 자리를 몇 분씩 차지하면 SSE
   * 하트비트가 발화하지 못한다 ({@code ChatImageJobExecutor}).
   */
  @Scheduled(cron = "${duckmoim.chat.purge.cron}", zone = KST)
  public void schedulePurge() {
    jobExecutor.submitIfIdle(running, this::purgeExpiredRooms);
  }

  /**
   * 한 회차를 이 스레드에서 끝까지 돈다. 스케줄러는 {@link #schedulePurge} 로 전용 스레드에 넘기고, 검사는 이것을 바로 부른다.
   *
   * <p><b>예외를 밖으로 던지지 않는다.</b> 스케줄러에서 예외가 올라가면 다음 실행이 오는지가 설정에 달린다. 여기서 잡아 남기면 다음 주기가 곧 재시도가 된다 —
   * 파기가 멱등이라 재시도가 안전하다.
   *
   * <p><b>0건이어도 남긴다.</b> 이 배치가 조용히 멈추면 처리방침이 고지한 보유 기간이 깨지는데, <b>지운 것이 없다는 로그가 없으면 멈춘 것과 구별되지
   * 않는다.</b> 주기가 하루에 한 번이라 값도 싸다.
   *
   * <p><b>집은 방 수와 파기한 방 수를 함께 남긴다.</b> 둘이 갈리면 저장소 삭제가 실패하고 있다는 뜻이다 — 인스턴스 역할에 {@code
   * s3:DeleteObject} 가 없을 때의 증상이 정확히 그 모양이라, 한쪽만 찍으면 그 고장이 안 보인다.
   */
  public void purgeExpiredRooms() {
    try {
      LocalDateTime cutoffInUtc = nowInUtc().minus(retention);
      ChatPurge total = purgeUntilDrained(cutoffInUtc);

      log.info(
          "[ChatPurgeBatch.purgeExpiredRooms] 보관 기간이 지난 방을 파기했다."
              + " picked={}, purged={}, messages={}, images={}, cutoff={}",
          total.picked(),
          total.purged(),
          total.messages(),
          total.images(),
          cutoffInUtc);
    } catch (Exception exception) {
      log.error("[ChatPurgeBatch.purgeExpiredRooms] 보관 기간이 지난 방의 파기에 실패했다.", exception);
    }
  }

  private ChatPurge purgeUntilDrained(LocalDateTime cutoffInUtc) {
    ChatPurge total = ChatPurge.empty();

    for (int chunks = 0; chunks < MAX_CHUNKS; chunks++) {
      List<Long> roomIds = chatPurgeService.findPurgeableRooms(cutoffInUtc, batchSize);
      ChatPurge chunk = purgeChunk(roomIds);
      total = total.plus(chunk);

      // 집은 수로 끊는다. 파기한 수로 끊으면 저장소 삭제가 실패한 주기에 아직 남은 방을
      // 두고 배치가 끝난다.
      if (roomIds.size() < batchSize) {
        return total;
      }

      // 한 청크가 가득 찼는데 하나도 못 지웠으면 저장소가 막힌 것이다. 계속 돌면 파기
      // 표시가 안 붙은 같은 방을 MAX_CHUNKS 번 다시 집는다.
      if (chunk.purged() == 0) {
        log.warn(
            "[ChatPurgeBatch.purgeUntilDrained] 집었으나 한 방도 파기하지 못했다 — 저장소 권한을 확인한다." + " picked={}",
            chunk.picked());
        return total;
      }
    }

    return total;
  }

  private ChatPurge purgeChunk(List<Long> roomIds) {
    ChatPurge chunk = ChatPurge.empty();

    for (Long roomId : roomIds) {
      chunk = chunk.plus(purgeOne(roomId));
    }

    return chunk;
  }

  /**
   * 방 하나를 파기한다 — 사진을 못박고, 저장소에서 지우고, 행과 메시지를 지운다.
   *
   * <p><b>저장소 삭제는 트랜잭션 밖이다.</b> 못박기가 커밋된 뒤라, 여기서 무엇이 실패해도 아직 살아 있는 방의 사진을 지우는 일은 생기지 않는다.
   *
   * <p><b>저장소가 실패하면 행을 남긴다.</b> 행을 지우면 객체 키를 되찾을 길이 없다. {@code DELETING} 으로 남은 행 때문에 이 방은 파기 표시를 받지
   * 못하고, 다음 주기가 다시 집는다.
   */
  private ChatPurge purgeOne(Long roomId) {
    List<ClaimedChatImage> claimed = chatPurgeService.claimImages(roomId);

    int images = 0;
    for (ClaimedChatImage image : claimed) {
      if (!storage.delete(image.objectKey())) {
        continue;
      }
      if (chatPurgeService.removeImage(image.imageId())) {
        images++;
      }
    }

    RoomPurge room = chatPurgeService.purgeRoom(roomId, nowInUtc());

    return new ChatPurge(1, room.purged() ? 1 : 0, room.deletedMessages(), images);
  }

  /** 저장된 시각이 UTC 라 기준 시각도 UTC 여야 한다 ({@code BaseEntity}). */
  private LocalDateTime nowInUtc() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
