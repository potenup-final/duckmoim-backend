package com.duckmoim.admin.service;

import com.duckmoim.admin.domain.AuditKind;
import com.duckmoim.admin.domain.AuditLog;
import com.duckmoim.admin.infra.AuditLogRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자의 행위를 감사 로그에 덧붙인다 (AD-05 · I-13).
 *
 * <p><b>이 티켓에 호출부가 없다.</b> 기록을 남기는 다섯 행위가 각각 AD-04(제재 · 해제) · CM-17(비밀 댓글 열람) · AD-07(블라인드) ·
 * AU-11(계정 파기) 소관이고 셋 다 아직 구현 전이다. 그래도 여기서 만드는 이유는, 셋이 각자 저장소를 주입받아 기록하면 같은 표를 세 방향으로 쓰게 되고 그때 남은
 * 줄은 고칠 수 없기 때문이다 (I-13).
 *
 * <p><b>부르는 쪽이 지켜야 할 것.</b> 기록은 행위의 부수 효과이므로 (도메인-모델링.md 「1. 유비쿼터스 언어」) 행위와 <b>같은 트랜잭션 안에서</b> 부른다.
 * {@code REQUIRES_NEW} 로 떼어 두지 않는다 — 떼면 제재가 실패해도 기록이 남아 일어나지 않은 일이 장부에 오른다.
 *
 * <p>신고 처리(AD-03)는 여기를 부르지 않는다. 그 이력은 {@code Report} 가 지므로 같은 사실을 두 곳에 두지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AuditLogRecorder {

  private final AuditLogRepository auditLogRepository;
  private final Clock clock;

  /**
   * 행위 한 건을 남긴다.
   *
   * <p><b>{@code targetType} 을 받지 않는다.</b> {@link AuditKind} 가 이미 갖고 있다 — 부르는 쪽이 둘을 따로 주면 어긋난 조합이
   * 만들어질 수 있고, 고칠 수 없어 영영 남는다.
   *
   * <p><b>{@code actorUserId} 는 회원번호다.</b> 인가는 카카오 회원번호로 판정하지만 (D-5) 기록에는 회원번호를 남긴다. 카카오 회원번호는 어느
   * 응답에도 나가지 않고, 화면에 내릴 이름을 얻으려면 어차피 회원번호로 조인해야 한다.
   *
   * @param detail 무엇을 왜 했는지의 한 줄. 없어도 된다
   */
  @Transactional
  public void record(Long actorUserId, AuditKind kind, Long targetId, String detail) {
    auditLogRepository.save(AuditLog.of(actorUserId, kind, targetId, detail, nowInUtc()));
  }

  /**
   * 저장은 UTC 다 (도메인-모델링.md 4장). 주입된 시계는 KST 라 그대로 {@code LocalDateTime.now(clock)} 을 부르면 아홉 시간 앞선 값이
   * 들어가고, 그 줄은 고칠 수 없다.
   */
  private LocalDateTime nowInUtc() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
