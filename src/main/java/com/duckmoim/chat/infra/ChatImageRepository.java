package com.duckmoim.chat.infra;

import com.duckmoim.chat.domain.ChatImage;
import com.duckmoim.chat.domain.ChatImageStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/** 채팅 이미지 저장소 (CH-14 · CH-17). */
public interface ChatImageRepository extends JpaRepository<ChatImage, Long> {

  /**
   * 아직 메시지에 실리지 않은 채 오래된 것들 (CH-17).
   *
   * <p><b>상태를 {@code NOT IN} 으로 쓰지 않고 목록으로 받는다.</b> 부르는 쪽이 {@code PENDING} · {@code CONFIRMED} 둘을
   * 넘기고, 새 상태가 생길 때 이 질의가 조용히 그것을 포함하지 않는다 — {@code ATTACHED} 말고 다 지우는 질의였다면 나중에 생길 「EXIF 처리 중」
   * 상태(CH-16)가 지워졌을 것이다.
   *
   * <p><b>오래된 것부터 준다.</b> 한 주기가 상한만큼만 지우므로, 순서가 없으면 같은 묶음을 반복해 집고 옛것이 남는다.
   *
   * <p>인덱스는 {@code idx_chat_image_orphan (status, created_at)} 이다.
   */
  List<ChatImage> findByStatusInAndCreatedAtBeforeOrderByIdAsc(
      List<ChatImageStatus> statuses, LocalDateTime threshold, Limit limit);
}
