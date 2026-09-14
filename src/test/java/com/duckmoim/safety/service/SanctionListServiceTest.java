package com.duckmoim.safety.service;

import static com.duckmoim.safety.SanctionFixture.aSanction;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.safety.domain.SanctionCursor;
import com.duckmoim.safety.domain.SanctionKind;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제재 목록 한 페이지의 조립 (AD-10).
 *
 * <p>정렬 · 필터 · 커서 경계는 {@code SanctionQueryRepositoryTest} 가 본다. 여기서는 <b>페이지를 어떻게 자르고 다음 커서를 어디로
 * 가리키는지</b>, 그리고 <b>저장소 결과가 화면 값으로 옮겨지는지</b>를 본다.
 *
 * <p>발효 시각을 주입된 시계에 맞춰 잡는다 — 활성 판정이 SQL 로 내려가 「지금」이 조건에 들어가므로, 고정된 과거 시각을 쓰면 전부 만료된 것으로 읽힌다.
 */
@SpringBootTest
@Transactional
class SanctionListServiceTest {

  private static final long USER_ID = 2L;

  @Autowired private SanctionListService sanctionListService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Clock clock;

  private LocalDateTime now;

  @DisplayName("한 페이지를 size 만큼 자르고 다음 커서를 준다.")
  @Test
  void findSanctions() {
    long soonest = suspended(3);
    long middle = suspended(10);
    suspended(30);

    SanctionSlice slice = sanctionListService.findSanctions(null, null, 2);

    assertThat(slice.items())
        .extracting(SanctionListView::sanctionId)
        .containsExactly(soonest, middle);
    assertThat(slice.hasNext()).isTrue();
    assertThat(slice.nextCursor()).isEqualTo(new SanctionCursor(until(10), middle));
  }

  @DisplayName("마지막 페이지에서는 nextCursor 가 없다.")
  @Test
  void findSanctions_isLastPage() {
    suspended(3);

    SanctionSlice slice = sanctionListService.findSanctions(null, null, 20);

    assertThat(slice.hasNext()).isFalse();
    assertThat(slice.nextCursor()).isNull();
  }

  /** 그 구간의 커서는 만료 자리가 비어 있어야 이어 읽기가 성립한다. */
  @DisplayName("만료 없는 제재로 페이지가 끝나면 커서의 만료 자리가 비어 있다.")
  @Test
  void findSanctions_nextCursorHasNoExpiry() {
    long banned = neverExpiring();
    neverExpiring();

    SanctionSlice slice = sanctionListService.findSanctions(null, null, 1);

    assertThat(slice.nextCursor()).isEqualTo(new SanctionCursor(null, banned));
  }

  @DisplayName("제재 한 건이 화면 값으로 옮겨진다.")
  @Test
  void findSanctions_carriesView() {
    long sanctionId = suspended(3);

    SanctionListView view = sanctionListService.findSanctions(null, null, 20).items().get(0);

    assertThat(view.sanctionId()).isEqualTo(sanctionId);
    assertThat(view.userId()).isEqualTo(USER_ID);
    assertThat(view.nickname()).isEqualTo("댓글덕후");
    assertThat(view.kind()).isEqualTo(SanctionKind.SUSPENDED);
    assertThat(view.reason()).isNotBlank();
    assertThat(view.until()).isEqualTo(until(3));
    assertThat(view.expiresAt()).isEqualTo(until(3));
  }

  /** {@code until} 은 관리자가 입력한 값이라 여기 없고, {@code expiresAt} 은 계산한 값이라 있다. */
  @DisplayName("경고는 until 이 없어도 만료 시각이 실린다.")
  @Test
  void findSanctions_carriesWarningExpiry() {
    aSanction().userId(USER_ID).kind(SanctionKind.WARNED).issuedAt(now()).insert(jdbc);

    SanctionListView view = sanctionListService.findSanctions(null, null, 20).items().get(0);

    assertThat(view.until()).isNull();
    assertThat(view.expiresAt()).isEqualTo(now().plusYears(1));
  }

  @DisplayName("종류로 거른 목록만 실린다.")
  @Test
  void findSanctions_filtersByKind() {
    long warned =
        aSanction().userId(USER_ID).kind(SanctionKind.WARNED).issuedAt(now()).insert(jdbc);
    suspended(3);

    SanctionSlice slice = sanctionListService.findSanctions(SanctionKind.WARNED, null, 20);

    assertThat(slice.items()).extracting(SanctionListView::sanctionId).containsExactly(warned);
  }

  private long suspended(int days) {
    return aSanction()
        .userId(USER_ID)
        .kind(SanctionKind.SUSPENDED)
        .issuedAt(now())
        .until(until(days))
        .insert(jdbc);
  }

  private long neverExpiring() {
    return aSanction().userId(USER_ID).kind(SanctionKind.BANNED).issuedAt(now()).insert(jdbc);
  }

  private LocalDateTime until(int days) {
    return now().plusDays(days);
  }

  /**
   * 저장이 UTC 라 주입된 KST 시계를 옮겨 읽는다 — 서비스가 하는 것과 같다.
   *
   * <p><b>한 번만 읽고 붙든다.</b> 부를 때마다 읽으면 초 경계를 넘는 순간 깔아 둔 값과 단언하는 값이 갈려 가끔 빨간불이 난다.
   */
  private LocalDateTime now() {
    if (now == null) {
      now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0);
    }

    return now;
  }
}
