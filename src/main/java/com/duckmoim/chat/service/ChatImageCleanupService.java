package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.ChatImage;
import com.duckmoim.chat.domain.ChatImageStatus;
import com.duckmoim.chat.domain.ChatImageStorage;
import com.duckmoim.chat.infra.ChatImageRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메시지에 실리지 않은 사진을 치운다 (CH-17).
 *
 * <p>검증 기준이 <b>「확정되지 않은 객체가 주기 뒤 사라진다」</b>다. 주기는 {@code ChatImageCleanupBatch} 가 지고 여기는 한 청크를 진다 —
 * {@code NotificationExpiryService} / {@code NotificationExpiryBatch} 와 같은 배치다.
 *
 * <p><b>두 종류를 함께 집는다.</b> {@code PENDING}(올리다 말았다)과 {@code CONFIRMED}(올렸는데 안 보냈다)다. 발급 시점에 행을 만든 것이
 * 앞쪽을 보이게 하려는 것이었다 (계획서 8.3).
 *
 * <p><b>저장소를 먼저 지우고 행을 지운다.</b> 순서가 뒤집히면 행이 사라진 뒤 객체가 남아 <b>아무도 모르는 쓰레기</b>가 된다 — 행이 없으면 다음 주기가 그
 * 객체를 찾을 길이 없다. 반대 순서의 실패는 「객체는 없고 행만 남음」이고, 그것은 다음 주기가 다시 집어 정리한다.
 *
 * <p><b>트랜잭션 안에서 저장소를 부른다.</b> 이 경로에는 그 대가가 없다 — 배치가 새벽에 돌고 요청 스레드가 아니며, 삭제가 실패해도 {@link
 * ChatImageStorage#delete} 가 던지지 않는다. 밖으로 빼면 커밋과 삭제 사이에 창이 생겨 위 순서가 무너진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatImageCleanupService {

  /** 집는 상태 둘. {@code ATTACHED} 는 건드리지 않는다 — 실려 있는 사진을 지우면 말풍선이 없는 객체를 가리킨다. */
  private static final List<ChatImageStatus> ORPHAN_STATUSES =
      List.of(ChatImageStatus.PENDING, ChatImageStatus.CONFIRMED);

  private final ChatImageRepository chatImageRepository;
  private final ChatImageStorage storage;

  /**
   * 기준 시각보다 오래된 고아를 상한만큼 치운다.
   *
   * <p><b>상태를 다시 본다.</b> 질의가 이미 걸렀지만 그 사이에 전송이 끼어들 수 있다 — 집은 행이 {@code ATTACHED} 로 바뀌었으면 건너뛴다.
   * {@code isOrphan} 한 줄이 그 창을 막는다.
   *
   * @param thresholdInUtc 이 시각보다 먼저 만들어진 것만 집는다
   * @param limit 한 청크가 집을 최대 건수
   */
  @Transactional
  public ChatImageCleanup deleteChunk(LocalDateTime thresholdInUtc, int limit) {
    List<ChatImage> orphans =
        chatImageRepository.findByStatusInAndCreatedAtBeforeOrderByIdAsc(
            ORPHAN_STATUSES, thresholdInUtc, Limit.of(limit));

    int deleted = 0;
    for (ChatImage orphan : orphans) {
      if (!orphan.isOrphan()) {
        continue;
      }
      if (!storage.delete(orphan.getObjectKey())) {
        // 행을 남긴다. 다음 주기가 다시 집는다 — 행을 지우면 객체를 찾을 길이 없어진다.
        continue;
      }

      chatImageRepository.delete(orphan);
      deleted++;
    }

    return new ChatImageCleanup(orphans.size(), deleted);
  }
}
