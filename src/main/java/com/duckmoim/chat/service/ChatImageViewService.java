package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.ChatImage;
import com.duckmoim.chat.domain.ChatImageStorage;
import com.duckmoim.chat.domain.ChatImageViewUrlCache;
import com.duckmoim.chat.domain.Message;
import com.duckmoim.chat.domain.SignedChatImageUrl;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatImageRepository;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.common.exception.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사진을 볼 수 있는 주소를 발급한다 (CH-15).
 *
 * <p><b>공개 주소를 쓰지 않기로 한 결정이 이 서비스를 있게 했다</b> (계획서 8.2 · V704). 주소를 저장해 두면 그것을 아는 누구나 보게 되고, 거기에는
 * <b>방을 나간 사람과 링크가 새어 나간 외부인</b>이 포함된다 (도메인 「가시성과 권한」). 그래서 볼 때마다 자격을 다시 묻고 짧은 수명의 서명을 발급한다.
 *
 * <p><b>묻는 것이 둘이다.</b>
 *
 * <pre>
 * ① 이 방의 현재 멤버인가        I-18 · CH-18. 나가면 그 순간 아니다
 * ② 그 사진이 실린 메시지가 아직 보이는가   CH-12 삭제 · AD-09 블라인드
 * </pre>
 *
 * <p><b>②가 없으면 지운 사진이 그대로 나간다.</b> 목록은 이미 {@code AuthoredMessage} 에서 보이지 않는 메시지의 {@code imageId} 를
 * 끊는데(PR #147 리뷰), 그 번호를 이미 받아 둔 클라이언트는 삭제 뒤에도 그 값을 쥐고 있다 — 발급 경로가 같은 판정을 안 하면 <b>목록에서만 사라지고 사진은 계속
 * 보이는</b> 상태가 된다. 본문만 끊고 {@code imageId} 를 흘려보냈던 그 실수와 같은 모양이다.
 *
 * <h2>이미지 번호가 아니라 메시지 번호로 받는다</h2>
 *
 * <p>「그 사진이 실린 메시지가 아직 보이는가」를 물으려면 메시지가 필요하다. 이미지 번호로 받으면 {@code chat_message} 를 {@code image_id} 로
 * 되짚어야 하는데 <b>그 열에 인덱스가 없고</b>(V704 는 메시지 → 이미지 방향만 인덱스가 되도록 열을 놓았다), 인덱스를 더하면 이 티켓에 마이그레이션이 생긴다.
 * 메시지 번호로 받으면 PK 조회 하나에 방 번호 · 상태 · 이미지 번호가 함께 나온다.
 *
 * <p>클라이언트도 목록에서 둘을 나란히 받으므로 (`messageId` · `imageId`) 더 쥐어야 하는 값이 없다.
 *
 * <h2>없는 것과 볼 수 없는 것을 갈라 답하지 않는다</h2>
 *
 * <p>없는 메시지 · 다른 방의 메시지 · 지운 메시지 · 사진 없는 메시지가 모두 {@code CHAT_MESSAGE_NOT_FOUND} 다. 갈라서 답하면 <b>그 번호의
 * 메시지가 존재한다는 사실</b>을 알려준다 — {@code ChatErrorCode} 의 이미지 네 줄이 같은 판단을 적어 두었다. 새 코드를 만들지 않은 것도 그래서다.
 */
@Service
public class ChatImageViewService {

  private final ChatRoomMembershipReader chatRoomReader;
  private final ChatMessageRepository chatMessageRepository;
  private final ChatImageRepository chatImageRepository;
  private final ChatImageViewUrlCache viewUrlCache;

  /**
   * <b>캐시를 빈으로 등록하지 않고 여기서 쥔다</b> ({@code ChatImagePolicy} 와 같은 배치). 이 서비스가 싱글턴이라 캐시도 하나이고, 캐시가 이
   * 경로 밖에서 쓰일 일이 없어 주입받을 이유가 없다.
   */
  public ChatImageViewService(
      ChatRoomMembershipReader chatRoomReader,
      ChatMessageRepository chatMessageRepository,
      ChatImageRepository chatImageRepository,
      ChatImageStorage storage,
      @Value("${duckmoim.chat.image.view-ttl}") Duration viewTtl,
      Clock clock) {
    this.chatRoomReader = chatRoomReader;
    this.chatMessageRepository = chatMessageRepository;
    this.chatImageRepository = chatImageRepository;
    this.viewUrlCache = new ChatImageViewUrlCache(storage, viewTtl, clock);
  }

  /**
   * 그 메시지에 실린 사진을 볼 수 있는 주소 (CH-15).
   *
   * <p><b>보낸 사람인지는 보지 않는다.</b> 대화에 실린 사진은 그 방 사람들이 보라고 올린 것이다 — 올린 사람만 볼 수 있다면 사진을 보낼 이유가 없다. 업로드
   * 경로({@code ChatImageService})가 올린 사람 본인만 확정하게 막는 것과 갈리는 지점이고, 그쪽은 <b>아직 대화에 실리지 않은</b> 사진이라 그렇다.
   *
   * <p><b>읽기라 제재를 보지 않는다</b> (I-14). 도메인 6장의 제재 축에서 읽기가 막히는 것은 {@code BANNED} 뿐이고 그것은 로그인 자체가 막힌다 —
   * 인터셉터도 같은 이유로 쓰기 메서드에만 걸려 있다.
   *
   * @throws BusinessException 방이 없으면 404, 멤버가 아니면 403, 그 밖의 모든 경우 {@code CHAT_MESSAGE_NOT_FOUND}
   */
  @Transactional(readOnly = true)
  public SignedChatImageUrl viewUrlOf(Long roomId, Long messageId, Long requesterId) {
    chatRoomReader.requireMember(roomId, requesterId);

    Message message = requireVisibleMessageWithImage(roomId, messageId);
    ChatImage image =
        chatImageRepository
            .findById(message.getImageId())
            .filter(found -> found.getRoomId().equals(roomId))
            .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND));

    return viewUrlCache.urlOf(image.getObjectKey());
  }

  /**
   * 그 방의 · 아직 보이는 · 사진이 실린 메시지를 집는다.
   *
   * <p><b>방 번호를 대조하는 것이 {@code ChatMessageDeleteService} 와 같은 이유다.</b> 경로가 두 값을 따로 주므로, 대조하지 않으면
   * <b>내가 멤버인 방의 번호를 붙여 남의 방 사진을 볼 수 있다</b> — 여기서는 그 구멍이 삭제보다 크다. 지우는 것은 자기 메시지뿐이지만 보는 것에는 그런 제한이
   * 없다.
   */
  private Message requireVisibleMessageWithImage(Long roomId, Long messageId) {
    return Optional.ofNullable(messageId)
        .flatMap(chatMessageRepository::findById)
        .filter(found -> found.getRoomId().equals(roomId))
        .filter(Message::isVisible)
        .filter(Message::hasImage)
        .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND));
  }
}
