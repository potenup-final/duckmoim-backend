package com.duckmoim.companion.service;

import com.duckmoim.catalog.domain.Event;
import com.duckmoim.catalog.exception.EventErrorCode;
import com.duckmoim.catalog.infra.EventRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.domain.Capacity;
import com.duckmoim.companion.domain.ChosenEvent;
import com.duckmoim.companion.domain.CompanionPost;
import com.duckmoim.companion.domain.MeetPoint;
import com.duckmoim.companion.infra.CompanionPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모집글 작성 (PO-01 · PO-02 · PO-03 · PO-05).
 *
 * <p><b>요청자가 실재하는 회원인지 여기서 보지 않는다.</b> API-설계.md 「1. 권한 등급」이 SIGNUP 판정을 관문 한 곳으로 모았고, {@code
 * SecurityConfig} 가 이 경로를 그 등급으로 닫아 두었다 — 토큰이 없으면 401, 가입 미완료면 403 이라 여기까지 오지 않는다.
 *
 * <p><b>제재 중 유저 차단(I-14)은 여기 없다.</b> 같은 관문의 일이고 {@code CommentCommandService} 가 같은 이유로 비워 두었다. 여기에
 * 판정을 넣으면 Safety 담당 티켓에서 지워야 한다.
 *
 * <p>검증은 전부 도메인이 진다. 이 클래스가 하는 일은 <b>행사를 풀어 넘기고 저장하는 것</b>뿐이다.
 */
@Service
@RequiredArgsConstructor
public class CompanionPostCommandService {

  private final CompanionPostRepository companionPostRepository;
  private final EventRepository eventRepository;

  /** 모집글을 연다. */
  @Transactional
  public WrittenCompanionPost create(CompanionPostWriteCommand command) {
    CompanionPost post =
        CompanionPost.open(
            command.hostId(),
            command.title(),
            command.content(),
            chosenEventOf(command),
            command.meetAt(),
            MeetPoint.of(command.meetPlace(), command.meetLat(), command.meetLng()),
            Capacity.of(command.capacity()));

    return WrittenCompanionPost.of(companionPostRepository.save(post), command.eventExternalId());
  }

  /**
   * 요청이 보낸 외부 식별자로 행사를 읽는다 (PO-02).
   *
   * <p><b>다른 컨텍스트의 저장소를 읽기만 한다.</b> 도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」이 애그리게이트끼리 객체 참조를 두지 말라고 한 것이지
   * 읽지 말라고 한 것이 아니다 — 같은 절이 <i>"이 규칙은 쓰기 모델의 것이다"</i> 라고 명시했다. 읽어서 {@link ChosenEvent} 로 옮기고 나면 그
   * 뒤로는 {@code Event} 를 붙들지 않는다.
   *
   * <p><b>없는 행사면 EVENT_NOT_FOUND 다.</b> 행사 상세(EV-07)가 이미 만들어 둔 코드를 그대로 쓴다 — 같은 뜻의 코드를 모집글 쪽에 새로 만들면
   * 클라이언트가 같은 상황에 두 이름을 받는다.
   *
   * <p><b>행사명은 원제가 없으면 대상명으로 떨어진다.</b> 화면-계약.md 「목록 `GET /api/v1/posts` — PO-08」이 <i>"행사를 안 고른 글은 둘
   * 다 null 이고, 카드는 그때 색 블록으로 떨어진다"</i> 고 정했다. 행사를 골랐는데 원제가 비어 있다고 색 블록이 되면 그 규칙과 어긋난다 — 원제는 선택 필드이고
   * 대상명은 필수 필드다 (화면-계약.md 「행사 (EV)」).
   */
  private ChosenEvent chosenEventOf(CompanionPostWriteCommand command) {
    if (!command.hasEvent()) {
      return null;
    }

    Event event =
        eventRepository
            .findByExternalId(command.eventExternalId())
            .orElseThrow(() -> new BusinessException(EventErrorCode.EVENT_NOT_FOUND));

    return new ChosenEvent(
        event.getId(), displayTitleOf(event), event.getImageUrl(), event.getEndsOn());
  }

  private static String displayTitleOf(Event event) {
    return event.getTitle() == null ? event.getSubject() : event.getTitle();
  }
}
