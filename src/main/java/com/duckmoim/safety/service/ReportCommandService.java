package com.duckmoim.safety.service;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.safety.domain.Report;
import com.duckmoim.safety.exception.ReportErrorCode;
import com.duckmoim.safety.infra.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신고 접수 (SF-01 · SF-02 · SF-07 · CM-14).
 *
 * <p>대상 확인 → 중복 확인 → 접수. 대상×사유 조합 검증은 애그리게이트가 하고 (도메인 규칙), 여기서는 저장소를 봐야 아는 것만 본다.
 */
@Service
@RequiredArgsConstructor
public class ReportCommandService {

  private final ReportRepository reportRepository;
  private final ReportTargetReader reportTargetReader;

  /**
   * 신고를 접수한다.
   *
   * <p><b>중복 차단이 두 겹이다</b> (SF-01 「동일 대상 중복 접수 차단」).
   *
   * <ol>
   *   <li>사전 조회 — 흔한 경우를 제약 위반 없이 409 로 돌려준다
   *   <li>유니크 제약 — <b>동시 요청 2건은 사전 조회를 둘 다 통과한다.</b> 실제 차단은 여기서 일어나고, 위반을 409 로 옮긴다
   * </ol>
   *
   * <p>도메인-모델링.md 「3.3 경계를 넘는 불변식」이 {@code I-01} 닉네임 유일성에 대해 정한 처리 방식과 같은 모양이다 — <i>"DB 유니크 제약. 위반을
   * 409로 변환"</i>.
   *
   * <p><b>{@code saveAndFlush} 인 이유</b> — {@code save} 만 하면 INSERT 가 트랜잭션 커밋까지 미뤄져 제약 위반이 이 메서드 밖에서
   * 터진다. 그러면 여기서 409 로 옮길 수 없고 500 이 나간다.
   */
  @Transactional
  public Long report(ReportCommand command) {
    reportTargetReader.requireExists(command.targetType(), command.targetId());
    requireNotReported(command);

    Report report =
        Report.of(
            command.reporterId(),
            command.targetType(),
            command.targetId(),
            command.reason(),
            command.detail());

    try {
      return reportRepository.saveAndFlush(report).getId();
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ReportErrorCode.REPORT_DUPLICATED);
    }
  }

  private void requireNotReported(ReportCommand command) {
    boolean reported =
        reportRepository.existsByReporterIdAndTargetTypeAndTargetId(
            command.reporterId(), command.targetType(), command.targetId());

    if (reported) {
      throw new BusinessException(ReportErrorCode.REPORT_DUPLICATED);
    }
  }
}
