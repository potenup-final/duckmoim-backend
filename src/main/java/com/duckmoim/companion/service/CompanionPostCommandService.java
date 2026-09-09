package com.duckmoim.companion.service;

import com.duckmoim.catalog.domain.Event;
import com.duckmoim.catalog.exception.EventErrorCode;
import com.duckmoim.catalog.infra.EventRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.domain.Capacity;
import com.duckmoim.companion.domain.ChosenEvent;
import com.duckmoim.companion.domain.CompanionPost;
import com.duckmoim.companion.domain.MeetPoint;
import com.duckmoim.companion.exception.PostErrorCode;
import com.duckmoim.companion.infra.CompanionPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모집글 작성 · 수정 · 마감 (PO-01 · PO-02 · PO-03 · PO-05 · PO-06 · PO-07).
 *
 * <p><b>요청자가 실재하는 회원인지 여기서 보지 않는다.</b> API-설계.md 「1. 권한 등급」이 SIGNUP 판정을 관문 한 곳으로 모았고, {@code
 * SecurityConfig} 가 이 경로를 그 등급으로 닫아 두었다 — 토큰이 없으면 401, 가입 미완료면 403 이라 여기까지 오지 않는다.
 *
 * <p><b>제재 중 유저 차단(I-14)은 여기 없다.</b> 같은 관문의 일이고 {@code CommentCommandService} 가 같은 이유로 비워 두었다. 여기에
 * 판정을 넣으면 Safety 담당 티켓에서 지워야 한다.
 *
 * <p><b>방장 판정(HOST)도 여기 없다.</b> API-설계.md 「1. 권한 등급」의 {@code HOST} 는 관문이 판정하지 않지만, 그렇다고 service 의
 * 일이 되는 것도 아니다 — 어느 모집글의 방장인지는 그 글이 아는 사실이라 판정이 {@code CompanionPost} 안에 있다.
 *
 * <p>검증은 전부 도메인이 진다. 이 클래스가 하는 일은 <b>글과 행사를 풀어 넘기고 저장하는 것</b>뿐이다.
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
            chosenEventOf(command.eventExternalId()),
            command.meetAt(),
            MeetPoint.of(command.meetPlace(), command.meetLat(), command.meetLng()),
            Capacity.of(command.capacity()));

    return WrittenCompanionPost.of(companionPostRepository.save(post), command.eventExternalId());
  }

  /**
   * 모집글을 고친다 (PO-06).
   *
   * <p>작성과 같은 순서다 — 행사를 풀어서 도메인에 넘긴다. {@code save} 를 부르지 않는 것은 이미 영속 상태인 엔티티라 트랜잭션이 끝날 때 더티 체킹이
   * 반영하기 때문이고, 여기서 다시 부르면 <b>저장이 필요한 것처럼 읽힌다.</b>
   *
   * <p><b>없는 글이 먼저다.</b> 존재하지 않는 모집글이면 행사를 조회하기도 전에 404 로 끝난다 — 없는 글에 대해 「그 행사가 없다」고 답하지 않는다.
   */
  @Transactional
  public WrittenCompanionPost edit(CompanionPostEditCommand command) {
    CompanionPost post = requirePost(command.postId());

    post.editByHost(
        command.requesterId(),
        command.title(),
        command.content(),
        chosenEventOf(command.eventExternalId()),
        command.meetAt(),
        MeetPoint.of(command.meetPlace(), command.meetLat(), command.meetLng()),
        Capacity.of(command.capacity()));

    return WrittenCompanionPost.of(post, command.eventExternalId());
  }

  /**
   * 방장이 모집을 완료한다 (PO-07).
   *
   * <p><b>락을 걸지 않는다.</b> 도메인-모델링.md 「3.1 경계와 트랜잭션 범위」가 <i>"방장이 마감하는 순간 이미 진행 중이던 작성 한 건이 닫힌 글에 들어올
   * 수 있다. 닫힌 글도 열람은 되고 사용자가 잃는 것이 없어 막지 않는다"</i> 고 정했다. 마감과 댓글 작성이 서로 기다리게 만들지 않는다.
   *
   * <p>커맨드 객체를 두지 않았다. 받는 것이 경로 변수와 요청자 둘뿐이고 요청 본문이 없다 — 사유를 받지 않으므로 (화면-계약.md 「방장 취소는 1차에서 뺐다」) 옮겨
   * 담을 요청 DTO 자체가 없다.
   */
  @Transactional
  public ClosedCompanionPost close(Long postId, Long requesterId) {
    CompanionPost post = requirePost(postId);

    post.closeByHost(requesterId);

    return ClosedCompanionPost.from(post);
  }

  private CompanionPost requirePost(Long postId) {
    return companionPostRepository
        .findById(postId)
        .orElseThrow(() -> new BusinessException(PostErrorCode.POST_NOT_FOUND));
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
   *
   * <p><b>작성과 수정이 같은 자리를 지난다.</b> 수정에서 행사를 바꾸면 스냅샷도 다시 복제해야 하는데 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」),
   * 그 「무엇을 복제하는가」가 두 곳에 갈리면 작성으로 만든 글과 수정한 글의 스냅샷이 달라진다.
   */
  private ChosenEvent chosenEventOf(String eventExternalId) {
    if (eventExternalId == null || eventExternalId.isBlank()) {
      return null;
    }

    Event event =
        eventRepository
            .findByExternalId(eventExternalId)
            .orElseThrow(() -> new BusinessException(EventErrorCode.EVENT_NOT_FOUND));

    return new ChosenEvent(
        event.getId(), displayTitleOf(event), event.getImageUrl(), event.getEndsOn());
  }

  private static String displayTitleOf(Event event) {
    return event.getTitle() == null ? event.getSubject() : event.getTitle();
  }
}
