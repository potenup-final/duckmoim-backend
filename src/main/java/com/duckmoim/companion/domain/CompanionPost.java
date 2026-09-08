package com.duckmoim.companion.domain;

import com.duckmoim.common.domain.BaseEntity;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.exception.PostErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 특정 행사에 함께 갈 사람을 구하는 게시글 (도메인-모델링.md 「1. 유비쿼터스 언어」).
 *
 * <p>Comment 가 이 엔티티에서 읽는 것은 셋뿐이다 — 열린 글인지(CM-01), 방장이 누구인지(CM-10 · 도메인-모델링.md 「7.1 가시성과 권한」), 그리고
 * 글이 존재하는지.
 *
 * <p><b>상태 전이의 주인은 이 애그리게이트 하나다</b> (도메인-모델링.md 「3.1 경계와 트랜잭션 범위」). 방장 마감(PO-07)이 여기 있고, 마감시각 경과로
 * 거는 배치(PO-14)도 별도 경로를 만들지 않고 {@link #closeWith} 를 지난다.
 */
@Entity
@Table(name = "companion_post")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompanionPost extends BaseEntity {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // 애그리게이트 밖은 ID 로만 참조한다 (도메인 3.2).
  @Column(name = "host_id", nullable = false)
  private Long hostId;

  @Column(name = "event_id")
  private Long eventId;

  // 행사명과 이미지는 조인이 아니라 스냅샷이다. 복제 대상은 이 둘뿐이다 (도메인 3.2).
  @Column(name = "event_title", length = 200)
  private String eventTitle;

  @Column(name = "event_image_url", length = 500)
  private String eventImageUrl;

  @Column(name = "title", nullable = false, length = 40)
  private String title;

  @Column(name = "content", length = 500)
  private String content;

  // 저장은 UTC 다. 판정 기준이 KST 인 것은 도메인-모델링.md 「4. 엔티티 · 값 객체 · 식별자」 가 정했다.
  @Column(name = "meet_at", nullable = false)
  private LocalDateTime meetAt;

  // 장소명과 좌표를 함께 갖는 값 객체다 (I-05).
  @Embedded private MeetPoint meetPoint;

  // 없으면 정원 미표시다. 값이 있으면 2~6 (I-03). 없을 때는 이 객체 자체가 null 이다.
  @Embedded private Capacity capacity;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private PostStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "closed_reason", length = 30)
  private ClosedReason closedReason;

  private CompanionPost(
      Long hostId,
      String title,
      String content,
      ChosenEvent event,
      LocalDateTime meetAtInUtc,
      MeetPoint meetPoint,
      Capacity capacity) {
    this.hostId = hostId;
    this.status = PostStatus.OPEN;

    assign(title, content, event, meetAtInUtc, meetPoint, capacity);
  }

  /**
   * 방장이 고칠 수 있는 값을 한 번에 옮긴다.
   *
   * <p>작성과 수정이 <b>같은 자리를 지난다.</b> 둘이 각자 필드를 대입하면 나중에 필드가 하나 늘 때 한쪽만 고치는 날이 오고, 그러면 새 필드가 수정으로는 안
   * 바뀌는 채로 남는다. 여기에 없는 것은 애초에 옮길 수 없는 것들이다 — {@code hostId} 는 인증에서 나오고 (남의 이름으로 쓰는 것을 막는 유일한 장치),
   * {@code status} 는 마감 경로만 건드린다.
   */
  private void assign(
      String title,
      String content,
      ChosenEvent event,
      LocalDateTime meetAtInUtc,
      MeetPoint meetPoint,
      Capacity capacity) {

    this.title = title;
    this.content = content;
    this.eventId = event == null ? null : event.id();
    this.eventTitle = event == null ? null : event.title();
    this.eventImageUrl = event == null ? null : event.imageUrl();
    this.meetAt = meetAtInUtc;
    this.meetPoint = meetPoint;
    this.capacity = capacity;
  }

  /**
   * 모집글을 연다 (PO-01).
   *
   * <p><b>모든 모집글은 {@code OPEN} 에서 출발한다.</b> 도메인-모델링.md 「6. 라이프사이클」의 전이도표가 모든 전이의 출발점을 {@code OPEN}
   * 으로 두었고, 다른 상태로 태어나는 경로가 없다.
   *
   * <p>{@code event} 가 {@code null} 이면 행사를 고르지 않은 글이다. 행사명 · 이미지가 비고 만남시각을 검증하지 않는다 (PO-02).
   *
   * <p><b>제목 40자 · 본문 500자를 여기서 다시 보지 않는다.</b> API-컨벤션.md 「Validation 규칙」이 단순 형식 검증을 Bean
   * Validation 으로 정했고, 컬럼도 그 길이라 둘을 지나지 않는 경로가 없다. {@code Comment} 가 본문 길이를 두고 내린 판단과 같다.
   *
   * @param meetAt 클라이언트가 보낸 오프셋 포함 시각. 저장은 UTC 로, 판정은 KST 로 한다 (도메인-모델링.md 「4. 엔티티 · 값 객체 · 식별자」)
   */
  public static CompanionPost open(
      Long hostId,
      String title,
      String content,
      ChosenEvent event,
      OffsetDateTime meetAt,
      MeetPoint meetPoint,
      Capacity capacity) {

    requireMeetAtWithinEvent(meetAt, event);

    return new CompanionPost(hostId, title, content, event, toUtc(meetAt), meetPoint, capacity);
  }

  /**
   * 방장이 모집글을 고친다 (PO-06).
   *
   * <p><b>{@code OPEN} 에서만 고칠 수 있다.</b> 도메인-모델링.md 「6. 라이프사이클」의 상태별 표가 {@code CLOSED} 의 수정을 「불가」로
   * 두었고, PO-06 의 검증 기준이 그때 409 다.
   *
   * <p><b>부분 수정이 아니라 전량 치환이다.</b> 화면 계약에 수정 payload 절이 없어 모양을 정해야 했고, {@code Comment} 의 수정이 같은 상황을
   * 같게 처리했다 — 작성 payload 에서 옮길 수 없는 것만 뺀 나머지를 다 받는다. 「안 보낸 필드」와 「비워서 지우는 필드」를 가르려면 필드마다 두 뜻을 구분하는
   * 규약이 필요한데, 그 규약이 어느 문서에도 없다.
   *
   * <p><b>만남시각을 다시 검증한다</b> (I-04). 도메인-모델링.md 「5. 불변식」이 이 불변식의 검증 시점을 <i>「생성·수정 시」</i>로 못박았다. 바뀐
   * 행사로 판정한다 — 행사를 함께 바꾸는 요청에서 옛 종료일로 보면 답이 틀린다.
   *
   * <p><b>행사 스냅샷도 함께 다시 복제한다.</b> 조인이 아니라 스냅샷이라 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」) 행사를 바꿔도 자동으로 따라오지
   * 않는다. 행사를 떼면 셋이 함께 {@code null} 이 된다.
   */
  public void editByHost(
      Long requesterId,
      String title,
      String content,
      ChosenEvent event,
      OffsetDateTime meetAt,
      MeetPoint meetPoint,
      Capacity capacity) {

    requireOpen();
    requireHost(requesterId);
    requireMeetAtWithinEvent(meetAt, event);

    assign(title, content, event, toUtc(meetAt), meetPoint, capacity);
  }

  /**
   * 방장이 모집을 완료한다 (PO-07).
   *
   * <p><b>사유를 받지 않는다.</b> 이 경로는 {@code MANUAL} 만 낸다 — 방장 취소를 1차 범위에서 뺐고 (화면-계약.md 「방장 취소는 1차에서 뺐다」)
   * 그래서 방장이 고를 사유가 애초에 없다. 되살릴 때 손댈 곳이 이 자리다.
   *
   * <p><b>멱등이 아니다.</b> 이미 마감된 글을 다시 마감하면 409 다 — 도메인-모델링.md 「6. 라이프사이클」이 {@code CLOSED} 를 종착으로 두고 둘
   * 사이 전이를 없앴다. 멱등을 요구하는 것은 배치(PO-14) 쪽이고, 그것은 사람이 누른 요청이 아니라 재실행되는 작업이라서다.
   *
   * <p><b>상태를 권한보다 먼저 본다.</b> {@code Comment} 가 같은 순서다 — 종착 상태에 도달한 리소스는 누가 요청하든 조작 대상이 아니다. 상세 조회가
   * {@code PUBLIC} 이라 (PO-11) 마감 여부는 이미 공개된 사실이고, 409 가 숨겨야 할 것을 알려주지 않는다.
   */
  public void closeByHost(Long requesterId) {
    requireOpen();
    requireHost(requesterId);

    closeWith(ClosedReason.MANUAL);
  }

  /**
   * 전이를 실제로 거는 유일한 자리다.
   *
   * <p>상태와 사유를 <b>함께</b> 옮긴다. 둘을 따로 두면 사유 없는 {@code CLOSED} 나 {@code OPEN} 인데 사유가 있는 상태가 만들어진다 —
   * 도메인-모델링.md 「6. 라이프사이클」의 전이도표에는 그런 칸이 없다.
   *
   * <p>PO-14 배치가 여기에 {@code MEET_TIME_PASSED} 를 넣는다. 그때도 이 메서드를 지나야 전이 규칙이 한 곳에 남는다.
   */
  private void closeWith(ClosedReason reason) {
    this.status = PostStatus.CLOSED;
    this.closedReason = reason;
  }

  /** 열린 글에만 손댈 수 있다 (PO-06 · PO-07). */
  private void requireOpen() {
    if (status != PostStatus.OPEN) {
      throw new BusinessException(PostErrorCode.POST_ALREADY_CLOSED);
    }
  }

  /**
   * 방장 본인인지 본다 (PO-06 · PO-07).
   *
   * <p>API-설계.md 「1. 권한 등급」의 {@code HOST} 는 관문이 판정하지 않는다 — 어느 모집글의 방장인지는 그 글을 읽어야 알기 때문이다. 그래서 판정이
   * 여기 있고, 관문은 {@code SIGNUP} 까지만 본다.
   */
  private void requireHost(Long requesterId) {
    if (!hostId.equals(requesterId)) {
      throw new BusinessException(PostErrorCode.POST_NOT_HOST);
    }
  }

  /**
   * 만남시각의 <b>KST 날짜</b>가 행사 종료일 이하인지 본다 (PO-02 · I-04).
   *
   * <p><b>시각 비교가 아니라 날짜 비교다.</b> 행사 {@code endsOn} 이 날짜까지만 있어 비교할 시각이 애초에 없다. 그리고 KST 날짜여야 한다 — 같은
   * 순간이라도 UTC 로 읽으면 하루 전이 되는 구간이 매일 아홉 시간씩 있다.
   *
   * <p><b>여기가 유일한 방어선이다.</b> 도메인-모델링.md 「확인이 남은 것」이 이 불변식에 DB 이중 방어를 두지 않기로 정했다 — 막으려면 행사 종료일을 모집글
   * 행에 복제해야 하는데, 그러면 크롤러가 종료일을 당기는 UPDATE 가 제약에 걸려 실패한다. 검증을 지나는 경로가 작성과 수정 둘뿐이라 애플리케이션 검증으로 충분하다고
   * 본 것이고, <b>셋째 경로가 생기면 재검토한다.</b>
   */
  private static void requireMeetAtWithinEvent(OffsetDateTime meetAt, ChosenEvent event) {
    if (event == null) {
      return;
    }
    if (meetAt.atZoneSameInstant(KST).toLocalDate().isAfter(event.endsOn())) {
      throw new BusinessException(PostErrorCode.POST_MEET_AT_AFTER_EVENT_END);
    }
  }

  private static LocalDateTime toUtc(OffsetDateTime meetAt) {
    return meetAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }
}
