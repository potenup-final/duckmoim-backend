package com.duckmoim.identity.domain;

import com.duckmoim.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Comment · Safety · Admin 을 먼저 개발하려고 세운 최소 형태다. 상태 전이 메서드가 없고 읽기만 된다.
 *
 * <p>댓글 응답의 작성자 블록(API 2-5), 비밀 댓글 열람 판정(도메인 7.1), 제재 대상, 관리자 인가(D-5) 가 전부 이 엔티티를 읽는다.
 *
 * <p>가입 · 프로필 · 탈퇴의 상태 전이는 Identity 담당이 채운다. 컬럼은 도메인 3.1 의 애그리게이트 경계대로 이미 다 있어 스키마를 다시 건드릴 일은 없다.
 */
@Entity
@Table(name = "user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "kakao_user_id", nullable = false, unique = true)
  private Long kakaoUserId;

  @Column(name = "nickname", length = 20, unique = true)
  private String nickname;

  @Column(name = "birth_year")
  private Integer birthYear;

  @Column(name = "intro", length = 100)
  private String intro;

  @Column(name = "profile_image_url", length = 500)
  private String profileImageUrl;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private SignupStatus status;

  // 저장 전용이다. 어느 응답에도 그대로 나가지 않고 lastSeen 구간으로만 나간다 (도메인 7.2).
  @Column(name = "last_seen_at")
  private LocalDateTime lastSeenAt;

  @Column(name = "withdrawn_at")
  private LocalDateTime withdrawnAt;
}
