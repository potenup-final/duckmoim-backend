package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.ChatImage;
import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.Message;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatImageRepository;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.domain.CompanionPost;
import com.duckmoim.companion.infra.CompanionPostRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메시지 한 건을 판정하고 저장한다 (CH-07 · CH-08).
 *
 * <p><b>트랜잭션 경계가 이 클래스다.</b> 재시도 판정과 중복 처리는 {@link ChatMessageSendService} 가 진다 — 유니크 제약 위반은
 * <b>커밋(또는 flush) 시점에 터지고 그 순간 영속성 컨텍스트가 롤백 표시로 죽는다.</b> 같은 트랜잭션 안에서 잡아 「기존 건」을 다시 읽으려 하면 그 조회 자체가
 * 성립하지 않는다. 그래서 <b>잡는 쪽이 트랜잭션 밖에 있어야 한다.</b>
 *
 * <p>빈을 둘로 나눈 것은 {@code NotificationDispatchBatch} / {@code NotificationDispatchService} 와 같은 배치이고,
 * 근거도 같다 — 같은 클래스 안에서 자기를 부르면 프록시를 지나지 않아 {@code @Transactional} 이 걸리지 않는다.
 *
 * <p><b>판정 순서가 검증 기준의 순서다.</b> 「비멤버 403」(CH-07) 다음에 「만남시각 + 7일 경과 409」(CH-08)다. 멤버가 아닌 사람에게 방이 읽기
 * 전용인지를 먼저 알려줄 이유가 없다.
 */
@Service
@RequiredArgsConstructor
public class ChatMessageWriter {

  private final ChatRoomRepository chatRoomRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final ChatImageRepository chatImageRepository;
  private final CompanionPostRepository companionPostRepository;
  private final Clock clock;

  /**
   * 판정을 지나 메시지를 저장한다.
   *
   * <p><b>{@code flush} 를 명시적으로 부른다.</b> 그러지 않으면 {@code uq_chat_message_sender_client_id} 위반이 이
   * 메서드가 반환된 뒤 커밋에서 터지고, 부르는 쪽은 예외의 종류만 보고 「어느 제약이 걸렸는지」를 알 수 없다. {@code
   * ChatRoomInviteService#invite} 가 같은 이유로 같은 것을 한다.
   *
   * <p><b>이미지가 있으면 같은 트랜잭션에서 {@code ATTACHED} 로 바꾼다</b> (CH-14). 이 클래스가 두 표를 쓰게 된 자리다 — 메시지가 커밋되고
   * 이미지가 그대로 {@code CONFIRMED} 로 남으면 <b>고아 정리 배치가 실려 있는 사진을 지운다.</b> 같은 트랜잭션이어야 그 창이 없다.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException 같은 식별자가 동시에 들어와 두 번째가 거부된 경우.
   *     {@link ChatMessageSendService} 가 잡아 기존 건을 돌려준다
   */
  @Transactional
  public SentMessage write(
      Long roomId, Long senderId, String clientMessageId, String content, Long imageId) {

    ChatRoom room =
        chatRoomRepository
            .findById(roomId)
            .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

    if (!room.isMember(senderId)) {
      throw new BusinessException(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
    }

    requireWritable(room);
    attachImageIfPresent(roomId, senderId, imageId);

    Message message =
        chatMessageRepository.save(
            Message.send(roomId, senderId, clientMessageId, content, imageId));
    chatMessageRepository.flush();

    return SentMessage.from(message);
  }

  /**
   * 사진을 이 메시지에 못박는다 (CH-14).
   *
   * <p><b>여기가 검증 기준 「업로드 확인 전 메시지 전송 시 400」이 나는 자리다.</b> 판정은 {@code ChatImage#attach} 가 쥐고, 여기서는 「그
   * 방에 그 사람이 올린 것인가」까지만 본다.
   *
   * <p><b>넷이 같은 코드로 답한다</b> — 없는 번호 · 남의 번호 · 다른 방의 번호 · 확인 전. 갈라서 답하면 「그 번호의 사진이 존재하며 남이 이미 썼다」는
   * 사실을 알려준다 ({@code ChatErrorCode} 의 이미지 네 줄 각주).
   *
   * <p><b>전송 순서가 「붙이고 저장」이다.</b> 반대로 하면 이미지가 틀렸을 때 이미 저장된 메시지를 되돌려야 하고, 같은 트랜잭션이라 롤백은 되지만 {@code
   * AUTO_INCREMENT} 번호 하나가 비어 커서가 건너뛴다.
   */
  private void attachImageIfPresent(Long roomId, Long senderId, Long imageId) {
    if (imageId == null) {
      return;
    }

    ChatImage image =
        chatImageRepository
            .findById(imageId)
            .filter(found -> found.isUploadedBy(roomId, senderId))
            .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_IMAGE_NOT_CONFIRMED));

    image.attach();
  }

  /**
   * 만남시각 + 7일이 지나지 않았는가 (CH-08 · I-21).
   *
   * <p><b>모집글을 읽는 이유는 방이 만남시각을 갖지 않기 때문이다.</b> 도메인-모델링.md 가 「쓸 수 있는지 여부는 모집글의 만남시각에서 계산한다」로 정해
   * {@code ChatRoom} 에 상태 컬럼이 없다. {@code ChatRoomInviteService} 가 방장 여부를 모집글에서 읽는 것과 같은 방향이고, 읽기만
   * 한다 — 한 트랜잭션에서 고치는 애그리게이트는 메시지 하나다.
   *
   * <p><b>모집글이 마감됐는지는 보지 않는다.</b> 검증 기준이 「방장이 모집을 완료해도 전송 200」이다.
   *
   * <p>모집글이 없으면 방도 없어야 하므로(CH-01 · I-16) {@code CHAT_ROOM_NOT_FOUND} 로 답한다 — 요청자에게 두 경우의 차이가 없다.
   */
  private void requireWritable(ChatRoom room) {
    CompanionPost post =
        companionPostRepository
            .findById(room.getPostId())
            .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

    if (!room.isWritable(post.getMeetAt(), clock)) {
      throw new BusinessException(ChatErrorCode.CHAT_ROOM_READ_ONLY);
    }
  }
}
