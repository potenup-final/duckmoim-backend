package com.duckmoim.catalog.domain;

import com.duckmoim.common.domain.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "external_id", nullable = false, length = 64, unique = true)
  private String externalId;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 20)
  private EventSource source;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 20)
  private EventKind kind;

  @Enumerated(EnumType.STRING)
  @Column(name = "subject_type", nullable = false, length = 20)
  private SubjectType subjectType;

  @Enumerated(EnumType.STRING)
  @Column(name = "trust", nullable = false, length = 20)
  private Trust trust;

  @Column(name = "subject", nullable = false, length = 100)
  private String subject;

  @Column(name = "title", length = 200)
  private String title;

  @Column(name = "starts_on", nullable = false)
  private LocalDate startsOn;

  @Column(name = "ends_on", nullable = false)
  private LocalDate endsOn;

  @Column(name = "open_hours", length = 100)
  private String openHours;

  @Column(name = "starts_at")
  private LocalTime startsAt;

  @Column(name = "perks", length = 500)
  private String perks;

  @Column(name = "conditions", length = 500)
  private String conditions;

  @Column(name = "source_url", nullable = false, length = 500)
  private String sourceUrl;

  @Column(name = "listing_url", length = 500)
  private String listingUrl;

  @Column(name = "reservation_url", length = 500)
  private String reservationUrl;

  @Column(name = "image_url", length = 500)
  private String imageUrl;

  @Column(name = "place_name", nullable = false, length = 100)
  private String placeName;

  @Column(name = "place_address", nullable = false, length = 200)
  private String placeAddress;

  @Column(name = "place_lat", nullable = false, precision = 10, scale = 7)
  private BigDecimal placeLat;

  @Column(name = "place_lng", nullable = false, precision = 10, scale = 7)
  private BigDecimal placeLng;

  @Enumerated(EnumType.STRING)
  @Column(name = "place_kind", nullable = false, length = 20)
  private PlaceKind placeKind;

  // Region 은 Event 애그리게이트 밖이라 객체 참조를 두지 않는다 (도메인 3.2).
  @Column(name = "region_id", nullable = false)
  private Long regionId;

  /**
   * 크롤러가 이 행사를 마지막으로 본 시각 (EV-03 · D-7).
   *
   * <p>원본에서 내려간 행사는 요청에 실려 오지 않을 뿐이라, 이 값이 오래된 것이 「사라졌다」의 유일한 신호다. 판정과 숨김은 목록 조회가 한다 — 상태 필드가 아니라
   * 갱신 시각이라서, 숨겨야 할 때 따로 UPDATE 를 돌 필요가 없다.
   */
  @Column(name = "last_crawled_at", nullable = false)
  private Instant lastCrawledAt;

  @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("sortOrder ASC")
  private List<Goods> goods = new ArrayList<>();

  /**
   * 수집한 행사를 새로 만든다 (EV-03).
   *
   * <p>생성자를 열지 않고 이름 있는 팩터리를 둔다 — 이 객체가 태어나는 경로가 크롤러 적재 하나뿐이라는 사실을 이름에 남긴다.
   */
  public static Event crawled(EventCrawl crawl, Long regionId, Instant crawledAt) {
    Event event = new Event();
    event.externalId = crawl.externalId();
    event.apply(crawl, regionId, crawledAt);
    return event;
  }

  /**
   * 이미 있는 행사를 수집 결과로 갱신한다 (EV-03 · I-12).
   *
   * <p><b>{@code externalId} 는 바꾸지 않는다.</b> 그 값으로 이 행을 찾아왔으므로 같을 수밖에 없고, 사용자에게 보이는 주소라(도메인 4장) 갱신
   * 경로에서 움직일 수 있으면 안 된다.
   *
   * <p>service 가 필드를 하나씩 대입하지 않는다 (도메인 규칙 「상태 전이 규칙은 도메인 객체 안에 둔다」). 재실행 멱등성이 여기서 나온다 — 같은 {@link
   * EventCrawl} 을 두 번 넣으면 두 번째는 값이 같아 dirty checking 이 UPDATE 를 내지 않는다.
   */
  public void syncFrom(EventCrawl crawl, Long regionId, Instant crawledAt) {
    apply(crawl, regionId, crawledAt);
  }

  private void apply(EventCrawl crawl, Long regionId, Instant crawledAt) {
    this.source = crawl.source();
    this.kind = crawl.kind();
    this.subjectType = crawl.subjectType();
    this.trust = crawl.trust();
    this.subject = crawl.subject();
    this.title = crawl.title();
    this.startsOn = crawl.startsOn();
    this.endsOn = crawl.endsOn();
    this.openHours = crawl.openHours();
    this.startsAt = crawl.startsAt();
    this.perks = crawl.perks();
    this.conditions = crawl.conditions();
    this.sourceUrl = crawl.sourceUrl();
    this.listingUrl = crawl.listingUrl();
    this.reservationUrl = crawl.reservationUrl();
    this.imageUrl = crawl.imageUrl();
    this.placeName = crawl.placeName();
    this.placeAddress = crawl.placeAddress();
    this.placeLat = crawl.placeLat();
    this.placeLng = crawl.placeLng();
    this.placeKind = crawl.placeKind();
    this.regionId = regionId;
    this.lastCrawledAt = crawledAt;
  }
}
