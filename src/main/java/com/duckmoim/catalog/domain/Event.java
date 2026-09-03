package com.duckmoim.catalog.domain;

import com.duckmoim.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
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
}
