package com.duckmoim.identity.service;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.User;
import com.duckmoim.identity.exception.UserErrorCode;
import com.duckmoim.identity.infra.UserRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 조회 — 닉네임 사전 확인과 내 정보 (AU-06 · API 설계 2-2).
 *
 * <p>쓰기는 {@link UserService} 가 한다. 조회를 갈라 둔 것은 트랜잭션 성질이 달라서다 — 여기는 전부 {@code readOnly} 다.
 */
@Service
@RequiredArgsConstructor
public class UserQueryService {

  private final UserRepository userRepository;
  private final SanctionReader sanctionReader;
  private final Clock clock;

  /**
   * 그 닉네임을 쓸 수 있는지 (AU-06).
   *
   * <p><b>확정이 아니다.</b> 이 답과 실제 저장 사이에 남이 같은 닉네임을 넣을 수 있다 — API 설계 2-2 가 <i>"사전 조회. 확정은 아니다"</i> 로
   * 적어 둔 것이 그 뜻이다. 확정은 {@code uk_user_nickname} 이 하고 {@link UserService} 가 위반을 409 로 옮긴다.
   *
   * <p>흔한 오타·중복을 <b>제약 위반 없이</b> 먼저 걸러 주는 것이 이 조회의 값이다.
   */
  @Transactional(readOnly = true)
  public boolean isNicknameAvailable(String nickname) {
    return !userRepository.existsByNickname(nickname);
  }

  /**
   * 내 정보를 읽는다.
   *
   * <p><b>탈퇴 계정은 없는 계정으로 본다.</b> 탈퇴가 소프트 삭제라 행이 남아 존재 여부만으로는 걸러지지 않는다 — 관문이 먼저 끊지만 여기서도 본다.
   */
  @Transactional(readOnly = true)
  public MyProfile findMyProfile(Long userId) {
    User user =
        userRepository
            .findById(userId)
            .filter(found -> !found.isWithdrawn())
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

    return MyProfile.of(user, sanctionReader.read(userId), clock);
  }
}
