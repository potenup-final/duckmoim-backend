package com.duckmoim.companion.infra;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.companion.domain.ClosedReason;
import com.duckmoim.companion.domain.CompanionPost;
import com.duckmoim.companion.domain.PostStatus;
import com.duckmoim.companion.domain.UserPostCursor;
import com.duckmoim.companion.domain.UserPostListQuery;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 유저가 쓴 모집글의 정렬 · 필터 · 커서 경계 (AU-09 · AU-10).
 *
 * <p>실제 MySQL 로 돈다. 커서 경계는 정렬과 튜플 비교가 개입해서 mock 으로는 검증되지 않고, 테스트 컨벤션이 H2 도 금지했다.
 *
 * <p><b>작성 시각을 손으로 고정한다.</b> 정렬 키가 같은 데이터를 만들어 경계를 봐야 하는데 {@code UTC_TIMESTAMP(6)} 에 맡기면 마이크로초가 갈려
 * 같은 시각이 만들어지지 않는다.
 *
 * <p><b>{@code V21} 시드 모집글이 이 표에 함께 있다.</b> 그래서 소유자를 시드가 쓰지 않는 회원번호로 잡는다 — 시드를 지우면 뒤 테스트의 전제가 사라진다.
 */
@SpringBootTest
@Transactional
class UserPostQueryRepositoryTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 1, 0, 0);

  private static final long ME = 2L;
  private static final long SOMEONE_ELSE = 4L;

  @Autowired private CompanionPostRepository companionPostRepository;
  @Autowired private JdbcTemplate jdbc;

  @DisplayName("내 모집글은 작성 최신순으로 온다.")
  @Test
  void findUserPostSlice_orderedByCreatedAtDesc() {
    long older = insertMine("먼저 쓴 글", BASE);
    long newer = insertMine("나중에 쓴 글", BASE.plusHours(1));

    List<AuthoredPost> read = read(query(ME, null, 10));

    assertThat(idsOf(read)).containsExactly(newer, older);
  }

  @DisplayName("남이 쓴 모집글은 내 내역에 오지 않는다.")
  @Test
  void findUserPostSlice_excludesOthers() {
    long mine = insertMine("내 글", BASE);
    aCompanionPost().title("남의 글").hostId(SOMEONE_ELSE).createdAt(BASE.plusHours(1)).insert(jdbc);

    List<AuthoredPost> read = read(query(ME, null, 10));

    assertThat(idsOf(read)).containsExactly(mine);
  }

  /** 모집글은 소프트 삭제가 없고(결정 D-3) 마감이 종착 상태다. 내역에서 빠지면 「내가 열었던 모집」을 되찾을 수 없다. */
  @DisplayName("마감된 내 모집글도 내역에 온다.")
  @Test
  void findUserPostSlice_includesClosed() {
    long closed =
        aCompanionPost()
            .title("마감된 내 글")
            .hostId(ME)
            .createdAt(BASE)
            .status(PostStatus.CLOSED)
            .closedReason(ClosedReason.MANUAL)
            .insert(jdbc);

    List<AuthoredPost> read = read(query(ME, null, 10));

    assertThat(idsOf(read)).contains(closed);
  }

  @DisplayName("다음 페이지 유무를 알려고 한 건을 더 읽는다.")
  @Test
  void findUserPostSlice_readsOneMore() {
    insertMine("첫째", BASE);
    insertMine("둘째", BASE.plusHours(1));
    insertMine("셋째", BASE.plusHours(2));

    assertThat(read(query(ME, null, 2))).hasSize(3);
  }

  /** {@code created_at} 은 중복이 생긴다. 그때 순서를 정하는 것이 {@code id} 이고, 그것이 {@code V23} 의 꼬리 컬럼이다. */
  @DisplayName("작성 시각이 같아도 id 가 순서를 정한다.")
  @Test
  void findUserPostSlice_sameCreatedAt() {
    long first = insertMine("같은 시각 A", BASE);
    long second = insertMine("같은 시각 B", BASE);

    List<AuthoredPost> read = read(query(ME, null, 10));

    assertThat(idsOf(read)).containsExactly(second, first);
  }

  /**
   * <b>AU-09 · AU-10 의 커서 경계다.</b> 정렬 키가 같은 글 셋을 두고 두 페이지로 나눠 읽는다.
   *
   * <p>부등호가 하나라도 어긋나면 <b>같은 시각의 글이 두 번 오거나 사라진다</b> — 이것이 API 설계 3장이 커서 타입을 목록마다 따로 두라고 한 이유다.
   */
  @DisplayName("작성 시각이 같은 글에서도 이어 읽기에 누락과 중복이 없다.")
  @Test
  void findUserPostSlice_cursorBoundary() {
    long first = insertMine("경계 A", BASE);
    long second = insertMine("경계 B", BASE);
    long third = insertMine("경계 C", BASE);

    // 한 건을 더 읽어 오므로 앞의 두 건이 실제 페이지다
    List<AuthoredPost> firstRead = read(query(ME, null, 2));
    CompanionPost lastOfPage = firstRead.get(1).post();
    UserPostCursor cursor = new UserPostCursor(lastOfPage.getCreatedAt(), lastOfPage.getId());

    List<AuthoredPost> secondRead = read(query(ME, cursor, 2));

    assertThat(idsOf(firstRead).subList(0, 2)).containsExactly(third, second);
    assertThat(idsOf(secondRead)).containsExactly(first);
  }

  @DisplayName("없는 회원번호로 물으면 아무것도 오지 않는다.")
  @Test
  void findUserPostSlice_unknownHost() {
    insertMine("내 글", BASE);

    assertThat(read(query(9_999_999L, null, 10))).isEmpty();
  }

  /** 작성자 블록이 함께 온다 — 목록(PO-08)과 같은 카드를 그리려면 닉네임과 최근 접속이 있어야 한다. */
  @DisplayName("모집글에 작성자 정보가 함께 온다.")
  @Test
  void findUserPostSlice_carriesAuthor() {
    insertMine("작성자 붙은 글", BASE);

    AuthoredPost read = read(query(ME, null, 10)).get(0);

    assertThat(read.nickname()).isNotBlank();
  }

  private long insertMine(String title, LocalDateTime createdAt) {
    return aCompanionPost().title(title).hostId(ME).createdAt(createdAt).insert(jdbc);
  }

  private static UserPostListQuery query(long hostId, UserPostCursor cursor, int size) {
    return new UserPostListQuery(hostId, cursor, size);
  }

  private List<AuthoredPost> read(UserPostListQuery query) {
    return companionPostRepository.findUserPostSlice(query);
  }

  private static List<Long> idsOf(List<AuthoredPost> read) {
    return read.stream().map(authored -> authored.post().getId()).toList();
  }
}
