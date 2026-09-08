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

    return POLICY.canWrite(activeAt(userId, now).orElse(null), now);
  }

  private Optional<Sanction> activeAt(Long userId, LocalDateTime now) {
    List<Sanction> candidates =
        sanctionRepository.findByUserIdAndReleasedAtIsNullOrderByIssuedAtDesc(userId);

    return candidates.stream().filter(sanction -> sanction.isActiveAt(now)).findFirst();
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
