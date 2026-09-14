package com.duckmoim.safety.service;

import com.duckmoim.safety.domain.Sanction;
import com.duckmoim.safety.domain.SanctionCursor;
import com.duckmoim.safety.domain.SanctionKind;
import com.duckmoim.safety.domain.SanctionListQuery;
import com.duckmoim.safety.infra.SanctionRepository;
import com.duckmoim.safety.infra.SanctionedUser;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 백오피스 제재 목록 조회 (AD-10).
 *
 * <p><b>가시성 판정이 없다.</b> 이 경로에 닿는 사람은 이미 관리자다 — 인가가 {@code /api/v1/admin/**} 전체에 관문 한 곳으로 걸려 있다 (D-5
 * 의 지켜야 할 셋 중 둘째).
 *
 * <p><b>감사 로그를 남기지 않는다.</b> AD-05 가 기록하는 일곱은 전부 관리자가 <b>무언가를 한</b> 행위다. 이 목록은 비밀 댓글 본문(CM-17)이나 신고된
 * 방의 대화(AD-08)처럼 특별히 가려 둔 것을 여는 것이 아니라, 관리자 화면의 첫 페이지다. 거기까지 남기면 장부가 조회 기록으로 덮여 「무슨 조치가 있었나」가 안
 * 보인다.
 *
 * <p><b>{@code SanctionQueryService} 와 나눠 둔다.</b> 그쪽은 모든 쓰기 요청이 지나는 길이라 한 회원의 제재를 읽고, 이쪽은 관리자 화면이
 * 전량을 훑는다. 한 클래스에 담으면 관문이 백오피스 목록 쿼리를 든 서비스를 주입받는다.
 *
 * <p><b>시계를 여기서 읽는다.</b> 활성 판정이 SQL 로 내려가므로 「지금」을 조회 조건에 실어야 한다 — 저장소가 스스로 시각을 읽으면 테스트가 시각을 고정할 수
 * 없다.
 */
@Service
@RequiredArgsConstructor
public class SanctionListService {

  private final SanctionRepository sanctionRepository;
  private final Clock clock;

  /**
   * 지금 제재 중인 회원을 한 페이지 읽는다.
   *
   * <p>거를 것이 없어 읽은 것이 그대로 한 페이지다 — 활성 판정이 이미 SQL 에서 끝났다.
   */
  @Transactional(readOnly = true)
  public SanctionSlice findSanctions(SanctionKind kind, SanctionCursor cursor, int size) {
    SanctionListQuery query = new SanctionListQuery(kind, nowInUtc(), cursor, size);
    List<SanctionedUser> read = sanctionRepository.findSlice(query);

    boolean hasNext = read.size() > query.size();
    List<SanctionedUser> page = hasNext ? read.subList(0, query.size()) : read;

    return new SanctionSlice(views(page), nextCursor(page, hasNext), hasNext);
  }

  private static List<SanctionListView> views(List<SanctionedUser> page) {
    return page.stream().map(SanctionListService::view).toList();
  }

  private static SanctionListView view(SanctionedUser read) {
    Sanction sanction = read.sanction();

    return new SanctionListView(
        sanction.getId(),
        sanction.getUserId(),
        read.nickname(),
        sanction.getKind(),
        sanction.getReason(),
        sanction.getIssuedAt(),
        sanction.getUntil(),
        sanction.getExpiresAt());
  }

  /** 다음 페이지의 시작점. 이 페이지의 마지막 제재를 가리킨다. 만료가 없는 제재면 커서의 그 자리도 비어 있다. */
  private static SanctionCursor nextCursor(List<SanctionedUser> page, boolean hasNext) {
    if (!hasNext) {
      return null;
    }

    Sanction last = page.get(page.size() - 1).sanction();
    return new SanctionCursor(last.getExpiresAt(), last.getId());
  }

  /**
   * 저장은 UTC 다. 주입된 시계는 KST 라 그대로 {@code LocalDateTime.now(clock)} 을 부르면 아홉 시간 앞선 값으로 만료를 판정하게 된다 —
   * {@code SanctionQueryService} 가 같은 판단을 했다.
   */
  private LocalDateTime nowInUtc() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
