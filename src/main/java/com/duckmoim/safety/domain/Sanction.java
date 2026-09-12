package com.duckmoim.safety.domain;

import com.duckmoim.common.domain.BaseEntity;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.safety.exception.SanctionErrorCode;
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
 * 유저에게 내린 제재 한 건 (AD-04).
 *
 * <p>신고 처리(AD-03)가 <b>유저에게</b> 내리는 조치다. 콘텐츠 축인 블라인드(AD-07)와 달리 계정에 남는다.
 *
 * <p><b>대상을 {@code userId} 로만 참조한다.</b> Safety → Identity 가 [ID 참조] 다 (컨텍스트 맵). {@code Report} 가 같은
 * 판단을 했다.
 *
 * <p><b>「제재가 없다」는 행이 없는 것으로 표현된다.</b> {@code NONE} 을 저장하지 않는다 — API-설계.md 2-7 이 해제를 {@code DELETE}
 * 로 정한 것과 같은 이유다.
 *
 * <p><b>해제해도 행을 지우지 않는다.</b> {@code releasedAt} 이 찍힐 뿐이다. {@code DELETE} 라는 것은 「{@code NONE} 을 POST
 * 하지 않는다」는 뜻이지 기록을 버린다는 뜻이 아니고, 같은 유저가 몇 번 제재받았는지는 백오피스가 판단에 쓰는 재료다.
 *
 * <p><b>만료를 저장하지 않는다.</b> 지금 유효한지는 {@link #isActiveAt} 이 매번 판정한다 — 배치가 없고, {@code I-11}(댓글 수)이
 * <i>"저장하지 않고 조회 시 센다"</i> 로 같은 판단을 했다. 저장하면 「푼 적 없는데 만료된」 상태를 누가 언제 갱신하는지가 새로 필요해진다.
 */
@Entity
@Table(name = "sanction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Sanction extends BaseEntity {

  /** {@code WARNED} 의 해소 기간. 도메인-모델링.md 「6. 라이프사이클」의 제재 축 표가 「조치일로부터 1년」으로 정했다. */
  private static final int WARNED_YEARS = 1;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // 애그리게이트 밖은 ID 로만 참조한다 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」).
  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 20)
  private SanctionKind kind;

  // 본인에게 그대로 보여주는 정보다 (AD-04 · AU-12). 그래서 필수다.
  @Column(name = "reason", nullable = false, length = 500)
  private String reason;

  @Column(name = "issued_at", nullable = false)
  private LocalDateTime issuedAt;

  @Column(name = "until")
  private LocalDateTime until;

  @Column(name = "released_at")
  private LocalDateTime releasedAt;

  private Sanction(
      Long userId, SanctionKind kind, String reason, LocalDateTime issuedAt, LocalDateTime until) {

    this.userId = userId;
    this.kind = kind;
    this.reason = reason;
    this.issuedAt = issuedAt;
    this.until = until;
  }

  /**
   * 제재를 건다 (AD-04).
   *
   * <p><b>{@code until} 은 {@code SUSPENDED} 일 때만 받는다.</b> 화면 계약이 <i>"{@code until} 은 {@code
   * SUSPENDED} 일 때만 값이 있다"</i> 고 정했다. 다른 종류에 실어 보내면 400 이다 — 조용히 버리면 관리자는 기간을 준 줄 알고 화면에는 안 나온다.
   *
   * <p><b>{@code SUSPENDED} 인데 {@code until} 이 없어도 400 이다.</b> 기간 정지인데 끝이 없으면 영구 정지와 구분되지 않는다.
   *
   * <p><b>사유는 비울 수 없다.</b> 본인에게 보여주는 정보라 빈 문자열이면 안내 화면이 이유 없이 뜬다.
   */
  public static Sanction of(
      Long userId, SanctionKind kind, String reason, LocalDateTime issuedAt, LocalDateTime until) {

    if (reason == null || reason.isBlank()) {
      throw new BusinessException(SanctionErrorCode.SANCTION_REASON_REQUIRED);
    }
    if (kind.hasUntil() != (until != null)) {
      throw new BusinessException(SanctionErrorCode.SANCTION_UNTIL_MISMATCH);
    }

    return new Sanction(userId, kind, reason, issuedAt, until);
  }

  /**
   * 관리자가 제재를 푼다 (AD-04).
   *
   * <p>이미 풀린 것을 또 풀 수 없다. 두 번째 요청이 성공하면 푼 시각만 덮어써진다.
   *
   * <p><b>만료된 제재도 풀 수 있다.</b> 만료는 저장값이 아니라 판정이라, 「지난 정지」와 「푼 정지」는 표에서 구분된다 — 그 구분을 남긴다.
   */
  public void releaseBy(LocalDateTime now) {
    if (releasedAt != null) {
      throw new BusinessException(SanctionErrorCode.SANCTION_ALREADY_RELEASED);
    }

    this.releasedAt = now;
  }

  /**
   * 이 시각에 유효한 제재인가.
   *
   * <p>풀렸으면 아니고, 기간이 지났으면 아니다.
   *
   * <ul>
   *   <li>{@code SUSPENDED} — {@code until} 이 지나면 스스로 풀린다
   *   <li>{@code WARNED} — 조치일로부터 1년
   *   <li>{@code AGE_HOLD} · {@code BANNED} — 스스로 풀리지 않는다. 관리자가 풀거나 (AGE_HOLD 는 본인이 답하거나) 그대로다
   * </ul>
   */
  public boolean isActiveAt(LocalDateTime now) {
    if (releasedAt != null) {
      return false;
    }

    return switch (kind) {
      case SUSPENDED -> now.isBefore(until);
      case WARNED -> now.isBefore(issuedAt.plusYears(WARNED_YEARS));
      case AGE_HOLD, BANNED -> true;
    };
  }

  /** 이 제재가 지금 쓰기를 막는가 (I-14). 유효하지 않으면 막지 않는다. */
  public boolean blocksWritingAt(LocalDateTime now) {
    return isActiveAt(now) && kind.blocksWriting();
  }

  /**
   * 이 제재가 지금 <b>비공개 읽기</b>를 막는가 (STAR-84). 유효하지 않으면 막지 않는다.
   *
   * <p>{@link #blocksWritingAt} 과 같은 모양이다 — 종류가 무엇을 막는지는 {@code SanctionKind} 가 알고, 지금 유효한지는 여기가
   * 곱한다. {@code BANNED} 는 해소가 없어 {@code isActiveAt} 이 늘 참이지만, 관리자가 푼 뒤에는 거짓이 되어야 한다.
   */
  public boolean blocksPrivateReadingAt(LocalDateTime now) {
    return isActiveAt(now) && kind.blocksPrivateReading();
  }
}
