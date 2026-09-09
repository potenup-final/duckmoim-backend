package com.duckmoim.admin.domain;

import com.duckmoim.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자 인가 판정용 화이트리스트다. 애그리게이트가 아니다 (도메인 모델링 2장).
 *
 * <p>인증은 카카오가 하고 인가는 이 표가 한다 (API 설계 D-5). {@code User} 에 권한 컬럼을 두지 않아 판정 근거가 아예 다른 테이블에 있고, 일반 유저가
 * 어떤 경로로도 관리자가 될 수 없다.
 *
 * <p>등록 API 를 만들지 않는다. 행 추가는 DB 에서 직접 한다.
 */
@Entity
@Table(name = "admin_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAccount extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "kakao_user_id", nullable = false, unique = true)
  private Long kakaoUserId;

  @Column(name = "granted_at", nullable = false)
  private LocalDateTime grantedAt;
}
