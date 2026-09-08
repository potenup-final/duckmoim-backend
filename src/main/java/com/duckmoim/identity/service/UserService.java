package com.duckmoim.identity.service;

import com.duckmoim.auth.service.AuthService;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.Profile;
import com.duckmoim.identity.domain.SignupInfo;
import com.duckmoim.identity.domain.User;
import com.duckmoim.identity.exception.UserErrorCode;
import com.duckmoim.identity.infra.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 쓰기 — 가입 정보 입력 · 프로필 수정 · 탈퇴 (AU-05 · AU-06 · AU-08 · AU-11).
 *
 * <p>카카오가 주는 것은 회원번호뿐이라(결정 D-2) 계정은 {@code PENDING_SIGNUP_INFO} 로 태어난다. <b>여기가 그 상태를 벗어나는 유일한
 * 창구다.</b>
 *
 * <p><b>프로필 수정을 별도 서비스로 두지 않았다.</b> 계획서 3장이 {@code ProfileService} 를 예상했는데, 그러면 <b>유니크 위반 → 409 변환이
 * 두 곳에 생긴다.</b> I-01 의 이중 방어에서 실제로 차단하는 것이 DB 제약이고 그 위반을 옮기는 자리가 갈라지면, 한쪽만 고쳐졌을 때 다른 경로가 조용히 500 을
 * 낸다. 둘 다 {@code user} 표 한 장을 쓰는 유스케이스라 여기 함께 둔다.
 */
@Service
@RequiredArgsConstructor
public class UserService {

  private final UserRepository userRepository;
  private final AuthService authService;
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
   *
   * <p><b>세 검사의 순서가 곧 에러 코드다.</b> 이중 제출의 두 번째 요청은 <b>같은 닉네임</b>을 다시 내므로 두 조건이 동시에 걸린다 — 이미 가입했고, 그
   * 닉네임은 (자기 것이라) 이미 쓰이고 있다. 상태 검사가 나중이면 「이미 사용 중인 닉네임입니다」가 나가는데, 그 이름을 쓰는 사람이 <b>본인</b>이라 사용자는 고칠
   * 수 없는 안내를 받는다. 요청 자체가 허용되지 않는다는 사실이 입력값의 흠보다 앞선다.
   */
  @Transactional
  public void completeSignup(SignupCommand command) {
    User user =
        userRepository
            .findByIdForUpdate(command.userId())
            .filter(found -> !found.isWithdrawn())
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

    requireSignupPending(user);
    requireNicknameAvailable(command.nickname());

    user.completeSignup(SignupInfo.of(command.nickname(), command.birthYear(), currentYear()));

    try {
      userRepository.flush();
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(UserErrorCode.USER_NICKNAME_DUPLICATED);
    }
  }

  /**
   * 닉네임과 한줄소개를 고친다 (AU-08).
   *
   * <p><b>닉네임 중복 409 의 두 번째 호출자다.</b> 변환 코드는 {@link #completeSignup} 과 같은 것을 쓴다 — 경로가 하나뿐일 때는 그 변환이
   * 한 곳인지가 검증되지 않았고, 여기가 붙으면서 드러난다.
   *
   * <p><b>닉네임이 그대로면 사전 조회를 건너뛴다.</b> 안 그러면 <b>자기 행</b>이 걸려 409 가 난다 — 프로필 화면이 닉네임을 그대로 두고 한줄소개만 고쳐
   * 보내는 것이 가장 흔한 요청인데, 그때마다 「이미 사용 중인 닉네임입니다」가 나가고 <b>그 이름을 쓰는 사람이 본인이라 고칠 수가 없다.</b> C 의 PR 리뷰에서
   * 이중 제출로 실측된 것과 같은 결함이다.
   *
   * <p>같은 이유로 저장소에 조회 메서드를 더하지 않았다. {@code existsByNicknameAndIdNot} 을 만들면 되지만, 「안 바뀌었으면 볼 필요가 없다」가
   * 더 정확하고 {@code UserRepository} javadoc 이 <i>"조회 메서드를 늘리지 않는다"</i> 로 못박아 두었다.
   *
   * <p><b>동시에 같은 닉네임으로 바꾸면 하나만 성공한다.</b> 사전 조회는 둘 다 통과하고 {@code uk_user_nickname} 이 차단한다 — 가입 경로와
   * 같은 모양이라 {@code flush} 도 직접 부른다.
   *
   * <p>가입 미완료 계정은 여기 도달하지 않는다 — 등급이 {@code SIGNUP} 이라 관문이 403 으로 끊는다 (AU-07). 그 계정의 수정 경로는 {@code
   * PUT /users/me/signup-info} 하나다.
   */
  @Transactional
  public void updateProfile(ProfileUpdateCommand command) {
    User user =
        userRepository
            .findByIdForUpdate(command.userId())
            .filter(found -> !found.isWithdrawn())
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

    if (isNicknameChanged(user, command.nickname())) {
      requireNicknameAvailable(command.nickname());
    }

    user.updateProfile(new Profile(command.nickname(), command.bio()));

    try {
      userRepository.flush();
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(UserErrorCode.USER_NICKNAME_DUPLICATED);
    }
  }

  /** 보내지 않았거나({@code null}) 지금 값과 같으면 바뀌는 것이 없다. */
  private boolean isNicknameChanged(User user, String nickname) {
    return nickname != null && !nickname.equals(user.getNickname());
  }

  /**
   * 계정을 탈퇴 처리한다 (AU-11).
   *
   * <p><b>토큰 정리까지 한 트랜잭션이다.</b> {@code AuthService.logout} 을 이어 부르고, 그쪽이 {@code @Transactional} 이라
   * 이 트랜잭션에 합류한다 — 뒤가 실패하면 탈퇴도 함께 롤백되므로 <b>「탈퇴는 됐는데 토큰이 남은」 중간 상태가 없다.</b> 사용자는 500 을 받고 다시 부르면
   * 처음부터 실행된다.
   *
   * <p><b>{@code identity.service → auth.service} 를 여기서 허용한다.</b> 두 컨텍스트는 이미 {@code user} 행 한 장을
   * 공유한다 — {@code logout} 이 지우는 것은 {@code refresh_token} 이지만 잔여 Access 를 끊는 {@code
   * tokensInvalidatedAt} 은 {@code User} 의 컬럼이고, 그 전이({@code invalidateAllTokens})도 {@code User} 의
   * 메서드다. 사이에 포트를 끼워도 그 공유가 없어지지 않으므로 우회 없이 직접 부른다 (PR #84 리뷰).
   *
   * <p><b>순서를 이렇게 두는 이유.</b> 탈퇴 전이를 먼저 실행해 <b>락과 탈퇴 검사를 한 곳에 모은다.</b> {@code logout} 은 탈퇴 여부를 보지 않고
   * {@code ifPresent} 로만 동작해서, 먼저 부르면 이미 탈퇴한 계정에도 무효화 시각을 다시 찍는다 — {@code User} 가 <i>"무효화를 반복해서 찍으면
   * 안 된다"</i> 고 적어 둔 그 자리다.
   *
   * <p><b>{@code flush} 를 부르지 않는다.</b> 닉네임을 <b>비우는</b> 것이라 유니크 제약을 위반할 수가 없다 — 제약 위반을 409 로 옮기는
   * {@code completeSignup} · {@code updateProfile} 과 다른 자리다.
   *
   * <p>회원 행을 잠그고 읽는다. B 티켓의 토큰 경로와 같은 락 순서라 데드락이 생기지 않는다.
   *
   * <p><b>주입된 {@code Clock} 을 쓰지 않는다.</b> 그 빈은 {@code Asia/Seoul} 이고(행사 종료일 판정이 KST 여야 해서 그렇게 정해졌다)
   * {@code withdrawn_at} 은 다른 타임스탬프와 같은 UTC 컬럼이다. 같은 이유를 {@code AuthService.now} 가 이미 적어 두었다. 이
   * 서비스의 {@code Clock} 은 「올해」를 KST 로 읽어야 하는 {@code currentYear} 전용이다.
   */
  @Transactional
  public void withdraw(Long userId) {
    User user =
        userRepository
            .findByIdForUpdate(userId)
            .filter(found -> !found.isWithdrawn())
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

    user.withdraw(LocalDateTime.now(ZoneOffset.UTC));

    authService.logout(userId);
  }

  /**
   * <b>{@code completeSignup} 이 같은 검사를 한 번 더 한다</b> — 도메인이 불변식의 검증 위치이고 여기는 <b>순서</b>를 정하는 자리다
   * (I-01 의 이중 방어와 같은 모양).
   *
   * <p><b>검사를 도메인 호출 뒤로 미룰 수는 없다.</b> {@code completeSignup} 이 영속 엔티티의 닉네임을 바꾸므로, 그 뒤에 오는 {@code
   * existsByNickname} 이 flush 를 유발해 <b>방금 쓴 자기 행</b>을 중복으로 읽는다 — 모든 정상 가입이 409 가 된다. 실측했다.
   */
  private void requireSignupPending(User user) {
    if (!user.isSignupPending()) {
      throw new BusinessException(UserErrorCode.USER_SIGNUP_INFO_ALREADY_SET);
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
