package com.duckmoim.companion.domain;

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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Comment 를 먼저 개발하려고 세운 최소 형태다. 상태 전이 메서드가 없고 읽기만 된다.
 *
 * <p>Comment 가 이 엔티티에서 읽는 것은 셋뿐이다 — 열린 글인지(CM-01), 방장이 누구인지(CM-10 · 도메인 7.1), 그리고 글이 존재하는지.
 *
 * <p>마감 전이(PO-07 · PO-14)와 생성 · 수정은 Companion 담당이 채운다. 상태 전이의 주인은 이 애그리게이트 하나이고 배치용 별도 경로를 만들지 않는다
 * (도메인 3.1).
 */
@Entity
@Table(name = "companion_post")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompanionPost extends BaseEntity {

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

  @Column(name = "meet_at", nullable = false)
  private LocalDateTime meetAt;

  @Column(name = "meet_place", nullable = false, length = 100)
  private String meetPlace;

  @Column(name = "meet_lat", nullable = false, precision = 10, scale = 7)
  private BigDecimal meetLat;

  @Column(name = "meet_lng", nullable = false, precision = 10, scale = 7)
  private BigDecimal meetLng;

  // 없으면 정원 미표시다. 값이 있으면 2~6 (I-03).
  @Column(name = "capacity")
  private Integer capacity;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private PostStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "closed_reason", length = 30)
  private ClosedReason closedReason;
}
