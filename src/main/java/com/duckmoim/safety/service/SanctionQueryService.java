package com.duckmoim.safety.service;

import com.duckmoim.safety.domain.Sanction;
import com.duckmoim.safety.domain.SanctionPolicy;
import com.duckmoim.safety.infra.SanctionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 그 회원이 지금 어떤 제재를 받고 있는지 읽는다 (AD-04 · AU-12 · I-14).
 *
 * <p><b>부르는 쪽이 둘이다.</b> AU-12 의 {@code /users/me} 안내와 I-14 의 쓰기 차단이 같은 답을 필요로 한다. 두 곳이 각자 저장소를 읽으면
 * 만료 판정이 두 벌이 되고, 한쪽만 고치는 날이 온다.
 *
 * <p><b>만료 판정을 SQL 에 넣지 않는다.</b> 저장소는 「푼 적 없는 제재」까지만 거르고, 지금 유효한지는 도메인이 판정한다 ({@code
 * Sanction#isActiveAt}). 조건에 「지금」이 들어가면 인덱스를 못 타고 같은 규칙이 SQL 과 도메인 두 곳에 생긴다.
 *
 * <p><b>실행·해제 서비스와 나눠 둔다.</b> 읽기는 모든 쓰기 요청이 지나는 길이고 쓰기는 관리자만 부르는 길이라, 한 클래스에 담으면 관문이 제재를 걸 수 있는
 * 서비스를 주입받게 된다.
 */
@Service
@RequiredArgsConstructor
public class SanctionQueryService {

  private static final SanctionPolicy POLICY = new SanctionPolicy();

  private final SanctionRepository sanctionRepository;
  private final Clock clock;

  /**
   * 지금 유효한 제재. 없으면 비어 있다.
   *
   * <p><b>여러 건이면 가장 최근 것을 고른다.</b> 활성 제재는 한 유저에 최대 하나라는 것이 이 티켓의 판단이지만 (도메인 6장 상태 축이 {@code NONE}
   * 에서만 출발한다), 규칙이 깨진 날 조용히 아무거나 고르지 않도록 순서를 정해 둔다.
   *
   * <p><b>{@link #canWrite} 와 갈리는 지점이다.</b> 안내는 하나만 보여주면 되지만 차단은 전량을 봐야 한다 — 한 건만 보면 나중에 걸린 경고가 앞선
   * 정지를 가린다.
   */
  @Transactional(readOnly = true)
  public Optional<ActiveSanction> findActive(Long userId) {
    return activeAt(userId, nowInUtc()).map(SanctionQueryService::view);
  }

  /**
   * 이 회원이 지금 새 글을 쓸 수 있는가 (I-14).
   *
   * <p>판정은 {@link SanctionPolicy} 가 한다 — 도메인-모델링.md 3.3 이 <i>"service 가 {@code Sanction} 을 조회해 넘기고
   * 판정만 맡긴다"</i> 고 정했다. 여기서 {@code kind} 를 직접 보면 그 판정이 두 곳이 된다.
   */
  @Transactional(readOnly = true)
  public boolean canWrite(Long userId) {
    LocalDateTime now = nowInUtc();

    return POLICY.canWrite(activeAllAt(userId, now), now);
  }

  /**
   * 지금 유효한 제재 <b>전량</b>.
   *
   * <p><b>차단 판정은 한 건만 보면 안 된다.</b> 정지와 경고가 함께 활성이면 {@code issuedAt DESC} 상 나중에 걸린 경고가 먼저 잡히고, 그 한
   * 건만 넘기면 정지 중인 유저가 통과한다. 저장소가 목록을 돌려주는 이유가 이것이다.
   *
   * <p>지금은 활성 제재가 최대 하나다 — {@code SanctionCommandService#sanction} 이 회원 행을 잠그고 중복을 막는다. 그래도 전량을 넘기는
   * 것은, 그 잠금이 옮겨지거나 새 생성 경로가 생겼을 때 <b>결과가 안전한 쪽으로 떨어지게</b> 하기 위해서다.
   */
  private List<Sanction> activeAllAt(Long userId, LocalDateTime now) {
    return sanctionRepository.findByUserIdAndReleasedAtIsNullOrderByIssuedAtDesc(userId).stream()
        .filter(sanction -> sanction.isActiveAt(now))
        .toList();
  }

  /** 안내에 쓸 한 건. 여럿이면 가장 최근 것이다 ({@link #findActive}). */
  private Optional<Sanction> activeAt(Long userId, LocalDateTime now) {
    return activeAllAt(userId, now).stream().findFirst();
  }

  private static ActiveSanction view(Sanction sanction) {
    return new ActiveSanction(
        sanction.getKind(), sanction.getReason(), sanction.getUntil(), sanction.getIssuedAt());
  }

  /**
   * 저장은 UTC 다 (도메인-모델링.md 4장). 주입된 시계는 KST 라 그대로 {@code LocalDateTime.now(clock)} 을 부르면 아홉 시간 앞선
   * 값으로 만료를 판정하게 된다.
   */
  private LocalDateTime nowInUtc() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
