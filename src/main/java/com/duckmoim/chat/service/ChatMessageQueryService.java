package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.MessageCursor;
import com.duckmoim.chat.domain.MessageListQuery;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.AuthoredMessage;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메시지 목록 조회 (CH-09).
 *
 * <p><b>멤버 판정이 이 클래스의 첫 줄이다.</b> 알림함({@code NotificationQueryService})은 질의 모양 자체가 방어선이라 판정이 없지만, 방은
 * 여러 사람이 공유하는 자원이라 「볼 수 있는가」를 물을 상대가 있다 — {@code I-18}(방 멤버가 아닌 사람은 메시지 본문에 도달할 수 없다)의 검증 위치가 「조회
 * 조립 단일화」다 (도메인-모델링.md 「5. 불변식」).
 *
 * <p><b>나간 사람도 막힌다.</b> {@code ChatRoom#isMember} 가 {@code leftAt} 이 찬 행을 멤버로 세지 않아서 {@code CH-18}
 * 의 「퇴장 후 메시지 조회 403」이 여기서 성립한다. <b>CH-18 자체는 다른 티켓이지만 조회 경로를 여는 것이 이 티켓이라</b> 판정을 빼면 구멍이 열린 채로
 * 나간다.
 *
 * <p><b>거를 것이 없어 읽은 것이 그대로 한 페이지다.</b> 지운 메시지도 자리표시자로 남으므로 (CH-12) 질의 뒤에 빠지는 행이 없다 — 댓글 목록이 「걸러지기
 * 전의 마지막」을 커서로 삼아야 했던 것과 갈리는 지점이다 (CM-11).
 */
@Service
@RequiredArgsConstructor
public class ChatMessageQueryService {

  private final ChatRoomRepository chatRoomRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final Clock clock;

  /** 한 방의 메시지를 한 페이지 읽는다. */
  @Transactional(readOnly = true)
  public MessageSlice findMessages(MessageListQuery query, Long requesterId) {
    requireMember(query.roomId(), requesterId);

    List<AuthoredMessage> read = chatMessageRepository.findSlice(query);

    boolean hasNext = read.size() > query.size();
    List<AuthoredMessage> page = hasNext ? read.subList(0, query.size()) : read;

    return new MessageSlice(views(page), nextCursor(page, hasNext), hasNext);
  }

  /**
   * 방이 있고 요청자가 그 방의 멤버인가 (I-18 · CH-18).
   *
   * <p>없는 방은 404 이고 멤버가 아니면 403 이다 — 방 상세(CH-06)와 전송(CH-07)이 쓰는 것과 같은 두 코드다. <b>「존재를 숨기려면 404」의 일반
   * 원칙과 반대 방향인데</b> CH-06 의 검증 기준이 403 을 명시했고, 같은 방에 대한 답이 경로마다 갈리면 그것이 더 이상하다.
   */
  private void requireMember(Long roomId, Long requesterId) {
    ChatRoom room =
        chatRoomRepository
            .findById(roomId)
            .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

    if (!room.isMember(requesterId)) {
      throw new BusinessException(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
    }
  }

  private List<MessageView> views(List<AuthoredMessage> page) {
    return page.stream().map(this::view).toList();
  }

  /**
   * 「지웠나」의 판정은 프로젝션이 쥔다. 여기는 그 결과로 <b>본문과 사진을 함께</b> 끊는다 (CH-12 · PR #147 리뷰).
   *
   * <p><b>둘을 따로 끊으면 한쪽이 샌다.</b> 처음에 본문만 끊고 옆줄의 {@code imageId} 는 그대로 통과시켰다. 지금은 번호만으로 아무것도 안 보여
   * 조용하지만, {@code CH-15} 가 그 번호로 「방 멤버 판정 → 서명 URL 발급」을 붙이는 순간 <b>지운 사진이 그대로 보인다</b> — 사용자가 「지웠다」고
   * 믿는 것은 말풍선 전체다. {@code BLINDED} 쪽이 더 나쁘다. 운영이 메시지를 가리는 이유가 대개 사진인데, 가려도 방 멤버 전원이 계속 볼 수 있으면
   * AD-09 가 아무 일도 안 한 셈이다.
   *
   * <p><b>키를 빼지 않고 {@code null} 로 둔다.</b> 사진 없는 메시지가 대다수라 {@code imageId} 는 원래 {@code null} 이
   * 정상값이고, 자리표시자 판정은 {@code status} 가 한다.
   */
  private MessageView view(AuthoredMessage message) {
    return new MessageView(
        message.messageId(),
        message.senderId(),
        message.display(clock),
        message.isVisible() ? message.content() : null,
        message.isVisible() ? message.imageId() : null,
        message.status(),
        message.createdAt());
  }

  /** 다음 페이지의 시작점. 이 페이지의 마지막 메시지를 가리킨다. */
  private static MessageCursor nextCursor(List<AuthoredMessage> page, boolean hasNext) {
    if (!hasNext) {
      return null;
    }

    return new MessageCursor(page.get(page.size() - 1).messageId());
  }
}
