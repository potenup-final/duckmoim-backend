package com.duckmoim.safety.infra;

import static com.duckmoim.safety.SanctionFixture.aSanction;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.safety.domain.SanctionCursor;
import com.duckmoim.safety.domain.SanctionKind;
import com.duckmoim.safety.domain.SanctionListQuery;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 백오피스 제재 목록의 정렬 · 필터 · 커서 경계 (AD-10).
 *
 * <p>실제 MySQL 로 돈다. 커서 경계는 정렬과 튜플 비교가 개입해서 mock 으로는 검증되지 않고, 테스트 컨벤션이 H2 도 금지했다.
 *
 * <p><b>이 검사가 특히 필요한 이유</b> — 정렬 축이 둘이다. 만료가 비어 있는 구간이 뒤로 가야 하고 커서가 그 구간에 들어서면 이어 읽는 조건 자체가 바뀐다.
 * 어긋나도 컴파일은 되고 페이지 경계만 조용히 어긋난다.
 *
 * <p>발효 시각을 손으로 고정한다. 만료가 같은 제재가 페이지 경계에 걸리는 경우를 만들어야 한다.
 *
 * <p>유저는 V11 시드를 쓴다 — 2 댓글덕후.
 */
@SpringBootTest
@Transactional
class SanctionQueryRepositoryTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 1, 0, 0);

  /** 「지금」. 제재는 전부 이 시각 이후에 만료되도록 깔고, 만료를 보는 검사만 따로 시각을 옮긴다. */
  private static final LocalDateTime NOW = BASE.plusDays(1);

  private static final long USER_ID = 2L;

  @Autowired private SanctionRepository sanctionRepository;
  @Autowired private JdbcTemplate jdbc;

  @DisplayName("제재가 만료 임박순으로 나온다.")
  @Test
  void findSlice() {
    long later = suspended(BASE.plusDays(30));
    long soonest = suspended(BASE.plusDays(3));
    long middle = suspended(BASE.plusDays(10));

    assertThat(idsOf(query(null, null, 20))).containsExactly(soonest, middle, later);
  }

  /** AD-10 의 검증 기준 ① 이 걸린 자리다 — 「제재 직후 목록에 나타나고 해제 후 사라진다」. */
  @DisplayName("푼 제재는 목록에서 빠진다.")
  @Test
  void findSlice_isReleased() {
    long active = suspended(BASE.plusDays(3));
    aSanction()
        .userId(USER_ID)
        .kind(SanctionKind.BANNED)
        .issuedAt(BASE)
        .releasedAt(NOW)
        .insert(jdbc);

    assertThat(idsOf(query(null, null, 20))).containsExactly(active);
  }

  /** 검증 기준 ② — 「기간 정지는 만료일이 지나면 목록에서 빠진다」. */
  @DisplayName("만료일이 지난 기간 정지는 목록에서 빠진다.")
  @Test
  void findSlice_isExpiredSuspension() {
    long alive = suspended(BASE.plusDays(3));
    suspended(BASE.plusHours(1));

    assertThat(idsOf(query(null, null, 20))).containsExactly(alive);
  }

  /** 경고의 해소는 조치일로부터 1년이다 (도메인 6장 제재 축). */
  @DisplayName("1년이 지난 경고는 목록에서 빠진다.")
  @Test
  void findSlice_isExpiredWarning() {
    long alive = warned(BASE);
    warned(BASE.minusYears(2));

    assertThat(idsOf(query(null, null, 20))).containsExactly(alive);
  }

  /** 앞에 두면 「끝이 없는 것이 가장 임박하다」가 되어 첫 화면이 영구 정지로 찬다. */
  @DisplayName("스스로 풀리지 않는 제재는 만료가 있는 제재 뒤에 온다.")
  @Test
  void findSlice_ordersNeverExpiringLast() {
    long banned = neverExpiring(SanctionKind.BANNED);
    long ageHold = neverExpiring(SanctionKind.AGE_HOLD);
    long suspended = suspended(BASE.plusDays(30));

    assertThat(idsOf(query(null, null, 20))).containsExactly(suspended, banned, ageHold);
  }

  @DisplayName("종류로 거르면 그 종류의 제재만 나온다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(SanctionKind.class)
  void findSlice_filtersByKind(SanctionKind kind) {
    long wanted = sanction(kind, BASE);
    for (SanctionKind other : SanctionKind.values()) {
      if (other != kind) {
        sanction(other, BASE);
      }
    }

    assertThat(idsOf(query(kind, null, 20))).containsExactly(wanted);
  }

  @DisplayName("종류를 주지 않으면 모든 종류가 나온다.")
  @Test
  void findSlice_hasNoKindFilter() {
    for (SanctionKind kind : SanctionKind.values()) {
      sanction(kind, BASE);
    }

    assertThat(query(null, null, 20)).hasSize(SanctionKind.values().length);
  }

  @DisplayName("다음 페이지 유무를 알려고 한 건을 더 읽는다.")
  @Test
  void findSlice_readsOneMore() {
    suspended(BASE.plusDays(3));
    suspended(BASE.plusDays(4));
    suspended(BASE.plusDays(5));

    assertThat(query(null, null, 2)).hasSize(3);
  }

  /** 만료 시각만으로 정렬하면 여기서 누락·중복이 난다. */
  @DisplayName("만료 시각이 같아도 id 가 순서를 정한다.")
  @Test
  void findSlice_hasSameExpiry() {
    LocalDateTime sameExpiry = BASE.plusDays(3);
    long first = suspended(sameExpiry);
    long second = suspended(sameExpiry);
    long third = suspended(sameExpiry);

    assertThat(idsOf(query(null, null, 20))).containsExactly(first, second, third);
    assertThat(idsOf(query(null, new SanctionCursor(sameExpiry, second), 20)))
        .containsExactly(third);
  }

  @DisplayName("커서 다음부터 이어 읽고 앞 페이지를 다시 주지 않는다.")
  @Test
  void findSlice_afterCursor() {
    long first = suspended(BASE.plusDays(3));
    long second = suspended(BASE.plusDays(4));
    long third = suspended(BASE.plusDays(5));

    List<SanctionedUser> next = query(null, new SanctionCursor(BASE.plusDays(3), first), 20);

    assertThat(idsOf(next)).containsExactly(second, third);
  }

  /** 만료 있는 구간을 다 읽고 넘어가는 자리다. 이 조건이 없으면 뒤 구간이 통째로 빠진다. */
  @DisplayName("만료가 있는 마지막 제재를 가리키는 커서는 만료 없는 구간으로 이어진다.")
  @Test
  void findSlice_crossesIntoNeverExpiring() {
    long suspended = suspended(BASE.plusDays(3));
    long banned = neverExpiring(SanctionKind.BANNED);

    List<SanctionedUser> next = query(null, new SanctionCursor(BASE.plusDays(3), suspended), 20);

    assertThat(idsOf(next)).containsExactly(banned);
  }

  /** 만료 있는 제재를 함께 집으면 이미 지나온 앞 구간이 다시 나온다. */
  @DisplayName("만료 없는 제재를 가리키는 커서는 앞 구간을 다시 주지 않는다.")
  @Test
  void findSlice_staysWithinNeverExpiring() {
    suspended(BASE.plusDays(3));
    long banned = neverExpiring(SanctionKind.BANNED);
    long ageHold = neverExpiring(SanctionKind.AGE_HOLD);

    List<SanctionedUser> next = query(null, new SanctionCursor(null, banned), 20);

    assertThat(idsOf(next)).containsExactly(ageHold);
  }

  @DisplayName("종류 필터와 커서를 함께 쓸 수 있다.")
  @Test
  void findSlice_filtersAndReadsAfterCursor() {
    long cursorId = suspended(BASE.plusDays(3));
    long wanted = suspended(BASE.plusDays(4));
    warned(BASE);

    List<SanctionedUser> next =
        query(SanctionKind.SUSPENDED, new SanctionCursor(BASE.plusDays(3), cursorId), 20);

    assertThat(idsOf(next)).containsExactly(wanted);
  }

  @DisplayName("제재받은 회원의 닉네임이 함께 실린다.")
  @Test
  void findSlice_carriesNickname() {
    suspended(BASE.plusDays(3));

    SanctionedUser found = query(null, null, 20).get(0);

    assertThat(found.nickname()).isEqualTo("댓글덕후");
    assertThat(found.sanction().getUserId()).isEqualTo(USER_ID);
  }

  private long suspended(LocalDateTime until) {
    return aSanction()
        .userId(USER_ID)
        .kind(SanctionKind.SUSPENDED)
        .issuedAt(BASE)
        .until(until)
        .insert(jdbc);
  }

  private long warned(LocalDateTime issuedAt) {
    return aSanction().userId(USER_ID).kind(SanctionKind.WARNED).issuedAt(issuedAt).insert(jdbc);
  }

  private long neverExpiring(SanctionKind kind) {
    return aSanction().userId(USER_ID).kind(kind).issuedAt(BASE).insert(jdbc);
  }

  /** 종류마다 {@code until} 이 있어야 하는지가 갈린다 — 픽스처가 알아서 맞춘다. */
  private long sanction(SanctionKind kind, LocalDateTime issuedAt) {
    return aSanction().userId(USER_ID).kind(kind).issuedAt(issuedAt).insert(jdbc);
  }

  private List<SanctionedUser> query(SanctionKind kind, SanctionCursor cursor, int size) {
    return sanctionRepository.findSlice(new SanctionListQuery(kind, NOW, cursor, size));
  }

  private static List<Long> idsOf(List<SanctionedUser> found) {
    return found.stream().map(read -> read.sanction().getId()).toList();
  }
}
