package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.ChatImage;
import com.duckmoim.chat.domain.ChatImageStorage;
import com.duckmoim.chat.domain.ChatImageViewUrlCache;
import com.duckmoim.chat.domain.Message;
import com.duckmoim.chat.infra.ChatImageRepository;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.common.exception.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
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
 * <h2>한 번에 여러 장을 발급한다 (PR #152 리뷰 ③)</h2>
 *
 * <p>처음에는 사진 한 장에 요청 하나였다. <b>사진 20장이 있는 방을 열면 20요청 · 60쿼리</b>가 되고, 서명 수명이 짧아 화면을 오래 열어 두면 같은 사진에
 * 대해 그 왕복이 다시 돈다.
 *
 * <pre>
 * 단건   20요청 × (방 1 + 메시지 1 + 이미지 1) = 60쿼리
 * 일괄    1요청 × (방 1 + 메시지 IN 1 + 이미지 IN 1) = 3쿼리   ← 사진 수와 무관하다
 * </pre>
 *
 * <p><b>목록 응답에 주소를 싣는 쪽은 고르지 않았다.</b> 수명이 분 단위인 값을 오래 사는 목록 항목에 박으면 화면에 남아 있는 동안 만료되고, 같은 값이 실시간
 * 팬아웃({@code MessageEvent})에도 실리게 된다 — 발급을 따로 두면 <b>필요할 때 다시 묻는</b> 것으로 끝난다.
 *
 * <h2>볼 수 없는 것은 응답에서 빠진다</h2>
 *
 * <p>없는 메시지 · 다른 방의 메시지 · 지운 메시지 · 사진 없는 메시지가 <b>모두 그냥 빠진다.</b> 넷을 갈라 답하면 그 번호의 메시지가 존재한다는 사실을
 * 알려주는데, 빠진 것끼리는 서로 구분되지 않아 단건일 때 넷을 {@code CHAT_MESSAGE_NOT_FOUND} 하나로 접었던 것과 결과가 같다.
 *
 * <p><b>한 장이 안 보인다고 나머지를 못 받게 하지 않는다.</b> 목록을 그리는 중에 남이 자기 사진을 지우는 것은 정상적인 일이고, 그때 400 을 내리면 화면 전체가
 * 사진 없이 뜬다.
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
   * 그 메시지들에 실린 사진을 볼 수 있는 주소 (CH-15).
   *
   * <p><b>방 멤버 판정은 한 번이다.</b> 요청 하나가 한 방을 가리키므로 사진 수만큼 되물을 이유가 없다 — 여기가 단건일 때와 견줘 쿼리가 줄어드는 자리다.
   *
   * <p><b>보낸 사람인지는 보지 않는다.</b> 대화에 실린 사진은 그 방 사람들이 보라고 올린 것이다 — 올린 사람만 볼 수 있다면 사진을 보낼 이유가 없다. 업로드
   * 경로({@code ChatImageService})가 올린 사람 본인만 확정하게 막는 것과 갈리는 지점이고, 그쪽은 <b>아직 대화에 실리지 않은</b> 사진이라 그렇다.
   *
   * <p><b>읽기라 제재를 보지 않는다</b> (I-14). 도메인 6장의 제재 축에서 읽기가 막히는 것은 {@code BANNED} 뿐이고 그것은 로그인 자체가 막힌다 —
   * 인터셉터도 같은 이유로 쓰기 메서드에만 걸려 있다.
   *
   * <p><b>물어본 순서를 지킨다.</b> 저장소가 돌려주는 순서는 질의 계획에 달렸고, 화면이 그 순서에 기대지 않더라도 <b>같은 입력에 같은 출력</b>인 편이
   * 디버깅에서 싸다.
   *
   * @return 볼 수 있는 것만. 없거나 볼 수 없는 번호는 <b>조용히 빠진다</b>
   * @throws BusinessException 방이 없으면 404, 멤버가 아니면 403
   */
  @Transactional(readOnly = true)
  public List<ChatImageView> viewUrlsOf(Long roomId, List<Long> messageIds, Long requesterId) {
    chatRoomReader.requireMember(roomId, requesterId);

    List<Long> asked = messageIds.stream().filter(Objects::nonNull).distinct().toList();
    Map<Long, Message> visible = visibleMessagesWithImage(roomId, asked);
    Map<Long, String> objectKeys = objectKeysOf(roomId, visible.values());

    return asked.stream()
        .map(visible::get)
        .filter(Objects::nonNull)
        .filter(message -> objectKeys.containsKey(message.getImageId()))
        .map(
            message ->
                new ChatImageView(
                    message.getId(), viewUrlCache.urlOf(objectKeys.get(message.getImageId()))))
        .toList();
  }

  /**
   * 그 방의 · 아직 보이는 · 사진이 실린 메시지들.
   *
   * <p><b>방 번호를 대조하는 것이 {@code ChatMessageDeleteService} 와 같은 이유다.</b> 경로의 방 번호와 메시지 번호가 따로 오므로,
   * 대조하지 않으면 <b>내가 멤버인 방의 번호를 붙여 남의 방 사진을 볼 수 있다</b> — 여기서는 그 구멍이 삭제보다 크다. 지우는 것은 자기 메시지뿐이지만 보는 것에는
   * 그런 제한이 없다.
   */
  private Map<Long, Message> visibleMessagesWithImage(Long roomId, List<Long> messageIds) {
    return chatMessageRepository.findAllById(messageIds).stream()
        .filter(message -> message.getRoomId().equals(roomId))
        .filter(Message::isVisible)
        .filter(Message::hasImage)
        .collect(Collectors.toMap(Message::getId, message -> message));
  }

  /**
   * 사진 번호 → 객체 키.
   *
   * <p><b>이미지 행의 방 번호도 본다.</b> 메시지 쪽에서 이미 걸렀으므로 여기서 걸릴 일이 없지만, 두 표에 같은 방 번호가 있고 <b>FK 가 없다</b>
   * (V704) — 어긋난 행이 생기는 날 사진이 새는 쪽이 아니라 안 보이는 쪽으로 기운다.
   */
  private Map<Long, String> objectKeysOf(Long roomId, Collection<Message> messages) {
    List<Long> imageIds = messages.stream().map(Message::getImageId).toList();

    return chatImageRepository.findAllById(imageIds).stream()
        .filter(image -> image.getRoomId().equals(roomId))
        .collect(Collectors.toMap(ChatImage::getId, ChatImage::getObjectKey));
  }
}
