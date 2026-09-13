package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.ChatImageStatus;
import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.infra.ChatImageRepository;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.chat.service.ClaimedChatImages.ClaimedChatImage;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보관 기간이 지난 방의 대화와 사진을 지우는 트랜잭션들 (CH-19).
 *
 * <p>명세가 <b>「모집글 마감 후 90일에 대화와 이미지를 파기한다. 배치는 멱등」</b>이고 검증 기준이 <b>「두 번 돌아도 결과가 같다」</b>다. 주기와 저장소
 * 호출은 {@code ChatPurgeBatch} 가 지고, 여기는 DB 쪽 걸음을 각자의 트랜잭션으로 진다 ({@code ChatImageCleanupService} /
 * {@code ChatImageCleanupBatch} 와 같은 배치다).
 *
 * <h4>순서가 이 클래스의 전부다</h4>
 *
 * <pre>
 * ① claimImages   그 방의 사진을 DELETING 으로 못박고 커밋
 * ② (배치)         저장소 객체 삭제                      ← 트랜잭션 밖. 롤백이 안 되는 일
 * ③ removeImage   DELETING 인 행만 삭제
 * ④ purgeRoom     메시지를 지우고, 사진 행이 남지 않았으면 파기를 못박는다
 * </pre>
 *
 * <p><b>사진이 메시지보다 먼저다.</b> 메시지를 먼저 지우면 {@code image_id} 참조가 끊겨 실려 있던 사진이 고아가 되는데, CH-17 정리 배치는
 * {@code ATTACHED} 를 건드리지 않아 <b>그 사진이 영영 안 지워진다.</b> 파기가 도중에 멈춰도 순서가 이렇게 되어 있으면 남는 쪽이 늘 사진 행이고, 그
 * 행은 다음 주기가 다시 집는다.
 *
 * <p><b>방과 멤버는 남긴다.</b> 명세가 파기 대상으로 적은 것은 「대화와 이미지」이고, 방 행을 지우면 CH-01a 가 없앤 「방이 없는 경우」 분기가 조회 경로에
 * 되살아난다.
 *
 * <p><b>잠그지 않는다</b> (ADR 0009). 인스턴스가 둘이라 이 배치도 둘이 도는데 삭제가 멱등이고, 두 인스턴스가 방 번호 순으로 같게 훑어 락 획득 순서가
 * 같다.
 */
@Service
@RequiredArgsConstructor
public class ChatPurgeService {

  private final ChatRoomRepository chatRoomRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final ChatImageRepository chatImageRepository;

  /**
   * 보관 기간이 지난 방을 오래된 것부터 상한만큼 집는다.
   *
   * <p><b>경계 시각을 받는다.</b> {@code Clock} 을 여기서 읽지 않는 것은 「지난 방」과 「안 지난 방」의 경계가 검증 대상이라서다 — 시각을 인자로 두면
   * 검사가 실행 시각에 결과를 맡기지 않는다 ({@code NotificationExpiryService} 와 같은 판단이다).
   *
   * @param cutoffInUtc 이 시각보다 <b>앞서</b> 마감된 모집글의 방이 대상이다. 경계에 정확히 걸친 것은 남는다
   */
  @Transactional(readOnly = true)
  public List<Long> findPurgeableRooms(LocalDateTime cutoffInUtc, int limit) {
    return chatRoomRepository.findPurgeableRoomIds(cutoffInUtc, limit);
  }

  /**
   * 그 방의 사진을 전부 {@code DELETING} 으로 못박고, 저장소에서 지울 목록을 준다.
   *
   * <p><b>이 메서드가 끝나면 커밋된다.</b> 그 뒤에야 배치가 저장소를 부른다 — 못박기와 객체 삭제가 한 트랜잭션에 있으면, 롤백되는 쪽(DB)과 롤백되지 않는
   * 쪽(저장소)이 갈려 행은 살아 있는데 객체만 사라진 사진이 남는다.
   *
   * <p><b>앞 주기에 못박혀 남은 행도 함께 돌려준다.</b> 저장소 삭제가 실패해 {@code DELETING} 으로 남아 있던 것들이고, 다시 못박지 않아도 조회에
   * 걸린다.
   */
  @Transactional
  public List<ClaimedChatImage> claimImages(Long roomId) {
    chatImageRepository.claimRoomForPurge(roomId);

    return chatImageRepository
        .findByRoomIdAndStatusOrderByIdAsc(roomId, ChatImageStatus.DELETING)
        .stream()
        .map(image -> new ClaimedChatImage(image.getId(), image.getObjectKey()))
        .toList();
  }

  /**
   * 저장소 객체를 지운 뒤 사진 행을 지운다.
   *
   * <p><b>{@code DELETING} 인 행만 지운다.</b> 다른 인스턴스의 배치가 먼저 지웠으면 0행이고 그것도 정상이다 ({@code
   * ChatImageCleanupService#removeClaimed} 와 같은 질의를 쓴다).
   *
   * @return 이번에 지웠으면 {@code true}
   */
  @Transactional
  public boolean removeImage(Long imageId) {
    return chatImageRepository.deleteClaimed(imageId) == 1;
  }

  /**
   * 메시지를 지우고 파기를 못박는다.
   *
   * <p><b>사진 행이 남아 있으면 못박지 않는다.</b> 저장소 삭제가 하나라도 실패한 방이고, 여기서 표시해 버리면 대상 목록에서 빠져 그 사진이 영영 남는다. 메시지는
   * 그때도 지운다 — 지우는 것이 목적이고, 다음 주기에 이 방은 메시지 0건이라 값이 싸다.
   *
   * <p><b>이미 파기된 방을 다시 만나도 안전하다.</b> 메시지 삭제가 0행이고 {@link ChatRoom#markPurged} 가 시각을 덮지 않는다 — 검증 기준
   * 「두 번 돌아도 결과가 같다」가 이 자리에서 성립한다.
   *
   * @param nowInUtc <b>UTC 기준</b> 파기 시각. 저장된 다른 시각과 같은 기준이어야 한다
   */
  @Transactional
  public RoomPurge purgeRoom(Long roomId, LocalDateTime nowInUtc) {
    int deletedMessages = chatMessageRepository.deleteByRoomId(roomId);

    if (chatImageRepository.existsByRoomId(roomId)) {
      return new RoomPurge(deletedMessages, false);
    }

    boolean purged =
        chatRoomRepository.findById(roomId).map(room -> room.markPurged(nowInUtc)).orElse(false);

    return new RoomPurge(deletedMessages, purged);
  }
}
