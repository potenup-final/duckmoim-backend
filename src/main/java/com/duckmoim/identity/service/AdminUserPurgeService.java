package com.duckmoim.identity.service;

import com.duckmoim.admin.domain.AuditKind;
import com.duckmoim.admin.service.AuditLogRecorder;
import com.duckmoim.auth.service.AuthService;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.User;
import com.duckmoim.identity.domain.UserWithdrawn;
import com.duckmoim.identity.exception.UserErrorCode;
import com.duckmoim.identity.infra.UserRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 계정을 파기한다 (AD-05).
 *
 * <p><b>탈퇴가 아니다.</b> 탈퇴(AU-11)는 본인이 {@code UserService} 로 하고 작성자 블록에 나가는 둘만 비운다. 파기는 관리자가 하고 개인정보
 * 컬럼을 비운다 — 회원번호 · 한줄소개 · 출생연도 · 최근 접속이 더 지워진다.
 *
 * <p><b>{@code UserService} 에 넣지 않았다.</b> 그쪽은 본인이 자기 계정에 하는 일들이고 (가입 정보 입력 · 프로필 수정 · 탈퇴) 이쪽은 관리자가
 * 남의 계정에 하는 일이다. 부르는 사람이 다르면 인가도 감사 로그도 다르다. {@code AdminCommentCommandService} · {@code
 * AdminChatReadService} 가 같은 방식으로 갈라져 있다.
 *
 * <p><b>서비스가 identity 에 있다.</b> {@code User} 애그리게이트를 여는 자리라서다. 백오피스 경로의 서비스는 <b>애그리게이트를 가진
 * 컨텍스트</b>에 두고 {@code admin} 의 기록기를 주입받는 것이 이 저장소의 방식이다 — {@code SanctionCommandService}(safety) ·
 * {@code AdminMessageBlindService}(chat) 가 그렇다.
 *
 * <p><b>감사 로그가 파기와 같은 트랜잭션에 남는다</b> (I-13). {@code AuditLogRecorder} 가 부르는 쪽에 요구하는 계약이고, {@code
 * REQUIRES_NEW} 로 떼면 파기가 실패해도 기록만 남아 <b>일어나지 않은 파기</b>가 장부에 오른다. 고칠 수 없어 영영 남는다.
 */
@Service
@RequiredArgsConstructor
public class AdminUserPurgeService {

  private final UserRepository userRepository;
  private final AuthService authService;
  private final AuditLogRecorder auditLogRecorder;
  private final ApplicationEventPublisher events;

  /**
   * 계정을 파기한다.
   *
   * <p><b>회원 행을 잠그고 읽는다.</b> 관리자 둘이 같은 계정을 동시에 파기하면 둘 다 「아직 파기 안 됨」을 보고 둘 다 장부에 줄을 남긴다 — 일어난 일은
   * 하나인데 두 줄이 되고, 감사 로그는 고칠 수 없다. {@code UserService.withdraw} 와 같은 락 순서라 데드락이 생기지 않는다.
   *
   * <p><b>자기 계정은 파기할 수 없다.</b> 파기가 카카오 회원번호를 비우는데 관리자 인가가 그 값으로 판정하므로 (D-5), 부른 사람이 그 자리에서 스스로 잠긴다.
   * 되돌리는 경로가 없다.
   *
   * <p><b>토큰을 끊는다.</b> 파기 대상이 지금 로그인 중일 수 있고, 회원번호를 비우는 것만으로는 이미 발급된 Access 가 최대 30분 더 산다. 파기된 계정이
   * 그 사이에 글을 쓰면 「탈퇴한 회원」이 쓴 글이 된다.
   *
   * <p><b>{@code UserWithdrawn} 을 언제나 발행한다.</b> 이미 탈퇴한 계정이면 구독은 벌써 지워졌지만, 듣는 쪽이 {@code
   * deleteByUserId} 라 멱등이라서 두 번 불려도 무해하다. 조건을 달면 <b>탈퇴 없이 바로 파기된 계정</b>의 푸시 구독이 남는 쪽으로 틀리기 쉽다 — 틀리는
   * 방향이 나쁘다. 탈퇴한 사람의 폰에 알림이 뜬다.
   *
   * <p><b>파기를 먼저 하고 기록한다.</b> 순서가 반대이면 막힌 요청에도 기록이 남는다.
   *
   * @param reason 무엇 때문에 파기했는지. 그대로 감사 로그의 {@code detail} 이 된다
   * @throws BusinessException 없는 회원이면 {@code USER_NOT_FOUND}, 자기 계정이면 {@code
   *     USER_CANNOT_PURGE_SELF}, 이미 파기됐으면 {@code USER_ALREADY_PURGED}
   */
  @Transactional
  public void purge(Long userId, Long adminUserId, String reason) {
    if (userId.equals(adminUserId)) {
      throw new BusinessException(UserErrorCode.USER_CANNOT_PURGE_SELF);
    }

    User user =
        userRepository
            .findByIdForUpdate(userId)
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

    user.purge(LocalDateTime.now(ZoneOffset.UTC));

    authService.logout(userId);
    events.publishEvent(new UserWithdrawn(userId));

    auditLogRecorder.record(adminUserId, AuditKind.PURGE, userId, reason);
  }
}
