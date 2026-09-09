package com.duckmoim.safety.service;

import com.duckmoim.admin.domain.AuditKind;
import com.duckmoim.admin.service.AuditLogRecorder;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.exception.UserErrorCode;
import com.duckmoim.identity.infra.UserRepository;
import com.duckmoim.safety.domain.Sanction;
import com.duckmoim.safety.exception.SanctionErrorCode;
import com.duckmoim.safety.infra.SanctionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 유저를 제재하고 푼다 (AD-04).
 *
 * <p><b>감사 로그가 제재와 같은 트랜잭션에 남는다</b> (AD-05 · 도메인-모델링.md 3.3 의 예외). {@code AuditLogRecorder} 가 부르는
 * 쪽에 요구하는 것이고, {@code REQUIRES_NEW} 로 떼면 제재가 실패해도 기록만 남아 <b>일어나지 않은 제재</b>가 장부에 오른다 ({@code I-13}).
 *
 * <p><b>신고({@code Report})를 손대지 않는다.</b> 한 신고에서 제재와 블라인드가 함께 나올 수도 아무것도 안 나올 수도 있어, 종결은 관리자가 AD-03
 * 경로로 따로 적는다. 여기서 함께 바꾸면 그 조합이 이 서비스로 밀려 들어온다.
 *
 * <p><b>조회 서비스와 나눠 둔다.</b> 읽기는 모든 쓰기 요청이 지나는 길이고 이쪽은 관리자만 부르는 길이다.
 */
@Service
@RequiredArgsConstructor
public class SanctionCommandService {

  private final SanctionRepository sanctionRepository;
  private final SanctionQueryService sanctionQueryService;
  private final UserRepository userRepository;
  private final AuditLogRecorder auditLogRecorder;
  private final Clock clock;

  /**
   * 제재를 건다 (AD-04).
   *
   * <p><b>이미 활성 제재가 있으면 409 다.</b> 도메인 6장의 제재 축이 {@code NONE} 에서만 출발하는 상태 머신이라, 겹쳐 거는 전이가 없다. 덮어쓰면
   * 앞 제재의 사유가 조용히 사라지고 본인에게 보이던 안내가 바뀐다 (AU-12).
   *
   * <p><b>회원 행을 먼저 잠근다.</b> 잠그지 않으면 관리자 둘이 같은 회원을 동시에 제재할 때 <b>둘 다 「활성 제재 없음」을 보고 둘 다 저장</b>해, 「활성
   * 제재는 최대 하나」가 깨진다. 걸 제재는 아직 행이 없어 잠글 대상이 없으므로 <b>회원 행</b>을 잠근다 — {@code
   * UserRepository#findByIdForUpdate} 가 <i>"모든 경로가 이 회원 행을 가장 먼저 잠그면 한 회원의 작업이 완전히 직렬화된다"</i> 고 적어
   * 둔 그 장치이고, {@code AuthService} 가 이미 같은 방식으로 쓴다.
   *
   * <p><b>없는 회원에게는 걸 수 없다.</b> 잠그려 읽으면서 존재도 함께 확인된다. 제재는 본인에게 안내를 보이고 쓰기를 막는 일이라, 대상이 없으면 둘 다 성립하지
   * 않는다. 탈퇴한 회원도 여기 포함된다 (정본의 {@code USER_NOT_FOUND} 가 「탈퇴 포함」이다).
   *
   * <p><b>거는 것을 먼저 하고 기록한다.</b> 순서가 반대이면 막힌 요청에도 기록이 남아 일어나지 않은 제재가 장부에 오른다.
   */
  @Transactional
  public Long sanction(SanctionCommand command) {
    userRepository
        .findByIdForUpdate(command.userId())
        .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

    if (sanctionQueryService.findActive(command.userId()).isPresent()) {
      throw new BusinessException(SanctionErrorCode.SANCTION_ALREADY_ACTIVE);
    }

    LocalDateTime now = nowInUtc();
    Sanction saved =
        sanctionRepository.save(
            Sanction.of(command.userId(), command.kind(), command.reason(), now, command.until()));

    auditLogRecorder.record(
        command.adminUserId(), AuditKind.SANCTION, command.userId(), detailOf(command));

    return saved.getId();
  }

  /**
   * 제재를 푼다 (AD-04).
   *
   * <p><b>대상이 그 회원의 제재인지 확인한다.</b> 경로에 {@code userId} 와 {@code sanctionId} 가 둘 다 있어, 남의 제재 번호를 넣으면
   * 엉뚱한 사람이 풀린다.
   *
   * <p>감사 로그의 대상은 <b>제재가 아니라 회원</b>이다. {@code AuditTargetType} 이 {@code USER} · {@code COMMENT}
   * 둘뿐이고, 「누구를 풀었는가」가 장부에서 읽혀야 하는 값이다.
   */
  @Transactional
  public void release(Long userId, Long sanctionId, Long adminUserId) {
    Sanction sanction =
        sanctionRepository
            .findById(sanctionId)
            .filter(found -> found.getUserId().equals(userId))
            .orElseThrow(() -> new BusinessException(SanctionErrorCode.SANCTION_NOT_FOUND));

    sanction.releaseBy(nowInUtc());

    auditLogRecorder.record(
        adminUserId, AuditKind.RELEASE, userId, "제재 %d 해제".formatted(sanctionId));
  }

  /**
   * 무엇을 왜 걸었는지의 한 줄.
   *
   * <p><b>사유를 그대로 싣지 않는다.</b> 감사 로그는 고칠 수 없고 (I-13) 사유는 본인에게 보여주는 문장이라 길다. 종류만 남기면 「누가 누구에게 무엇을
   * 걸었는가」가 읽히고, 사유 자체는 {@code sanction} 표에 있다.
   */
  private static String detailOf(SanctionCommand command) {
    return "%s 제재".formatted(command.kind().name());
  }

  /** 저장은 UTC 다 (도메인-모델링.md 4장). 주입된 시계는 KST 라 그대로 쓰면 아홉 시간 앞선 값이 들어간다. */
  private LocalDateTime nowInUtc() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
