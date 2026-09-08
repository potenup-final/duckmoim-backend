package com.duckmoim.identity.service;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.SignupInfo;
import com.duckmoim.identity.domain.User;
import com.duckmoim.identity.exception.UserErrorCode;
import com.duckmoim.identity.infra.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입 정보 입력 (AU-05 · AU-06).
 *
 * <p>카카오가 주는 것은 회원번호뿐이라(결정 D-2) 계정은 {@code PENDING_SIGNUP_INFO} 로 태어난다. <b>여기가 그 상태를 벗어나는 유일한
 * 창구다.</b>
 */
@Service
@RequiredArgsConstructor
public class UserService {

  private final UserRepository userRepository;
  private final Clock clock;

  /**
   * 닉네임과 출생연도를 채워 활동할 수 있는 계정으로 만든다.
   *
   * <p><b>닉네임 유일성(I-01)을 두 겹으로 막는다</b> — 도메인 3.3 이 정한 처리 방식이다.
   *
   * <ol>
   *   <li>사전 조회 — 흔한 경우를 제약 위반 없이 409 로 돌려준다
   *   <li>유니크 제약 — <b>동시 요청 2건은 사전 조회를 둘 다 통과한다.</b> 실제 차단은 여기서 일어나고, 위반을 409 로 옮긴다
   * </ol>
   *
   * <p><b>{@code flush} 를 직접 부르는 이유</b> — {@code completeSignup} 은 이미 영속된 엔티티를 바꾸므로 UPDATE 가 트랜잭션
   * 커밋까지 미뤄진다. 그러면 제약 위반이 이 메서드 <b>밖에서</b> 터져 409 로 옮길 수 없고 500 이 나간다.
   *
   * <p><b>회원 행을 잠그고 읽는다.</b> 잠그지 않으면 같은 사용자의 이중 제출 둘이 모두 {@code PENDING} 을 읽고 지나가, 「이미 입력했다」 판정이
   * 조용히 무력해진다. B 티켓의 토큰 경로와 같은 락 순서라 데드락도 생기지 않는다.
   *
   * <p><b>탈퇴 계정은 없는 계정으로 본다</b> — 탈퇴가 소프트 삭제라 행이 남아서 존재 여부만으로는 걸러지지 않는다.
   */
  @Transactional
  public void completeSignup(SignupCommand command) {
    User user =
        userRepository
            .findByIdForUpdate(command.userId())
            .filter(found -> !found.isWithdrawn())
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

    requireNicknameAvailable(command.nickname());

    user.completeSignup(SignupInfo.of(command.nickname(), command.birthYear(), currentYear()));

    try {
      userRepository.flush();
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(UserErrorCode.USER_NICKNAME_DUPLICATED);
    }
  }

  private void requireNicknameAvailable(String nickname) {
    if (userRepository.existsByNickname(nickname)) {
      throw new BusinessException(UserErrorCode.USER_NICKNAME_DUPLICATED);
    }
  }

  /** 「올해」의 기준은 KST 다 (도메인 4장). {@code Clock} 빈이 {@code Asia/Seoul} 이라 그대로 쓴다. */
  private int currentYear() {
    return LocalDate.now(clock).getYear();
  }
}
