package com.duckmoim.companion.service;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.domain.ClosedReason;
import com.duckmoim.companion.domain.PostCursor;
import com.duckmoim.companion.domain.PostListQuery;
import com.duckmoim.companion.domain.PostStatus;
import com.duckmoim.companion.exception.PostErrorCode;
import com.duckmoim.identity.domain.LastSeen;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 목록 조회의 검증 기준 (PO-08).
 *
 * <p>실제 MySQL 로 돈다. 커서 경계는 정렬과 튜플 비교가 개입해서 mock 으로는 검증되지 않고, 테스트 컨벤션이 H2 도 금지했다.
 *
 * <p><b>시계를 고정한다.</b> 방장의 최근 접속을 구간으로 줄이는 계산이 현재 시각을 쓴다 (도메인-모델링.md 「7.2 최근 접속일 노출」) — 놓아두면 같은 데이터가
 * 오늘과 내일 다른 구간으로 나온다.
 *
 * <p>매 테스트가 시드(V21)를 지우고 자기 데이터만 넣는다. {@code @Transactional} 롤백이 시드를 되돌린다.
 */
@SpringBootTest
@Transactional
class CompanionPostQueryServiceTest {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 8, 12, 0);
  private static final LocalDateTime MEET_AT = LocalDateTime.of(2026, 10, 1, 0, 0);

  private static final long HOST_ID = 1L;

  @TestConfiguration
  static class FixedClockConfig {

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
    }
  }

  @Autowired private CompanionPostQueryService companionPostQueryService;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM comment");
    jdbc.update("DELETE FROM companion_post");
  }

  @DisplayName("목록은 만남시각 임박순으로 나온다.")
  @Test
  void findPosts() {
    long second = post(MEET_AT.plusDays(2));
    long first = post(MEET_AT.plusDays(1));

    PostSlice slice = companionPostQueryService.findPosts(query(null, null, 20));

    assertThat(idsOf(slice)).containsExactly(first, second);
  }

  /** 더 읽은 한 건은 다음 페이지 유무를 판정하는 데만 쓰고 응답에서 잘라낸다. */
  @DisplayName("한 페이지에 size 만큼만 담고 다음 페이지가 있음을 알린다.")
  @Test
  void findPosts_hasNext() {
    long first = post(MEET_AT.plusDays(1));
    post(MEET_AT.plusDays(2));

    PostSlice slice = companionPostQueryService.findPosts(query(null, null, 1));

    assertThat(idsOf(slice)).containsExactly(first);
    assertThat(slice.hasNext()).isTrue();
    assertThat(slice.nextCursor()).isEqualTo(new PostCursor(MEET_AT.plusDays(1), first));
  }

  @DisplayName("마지막 페이지는 다음 커서를 주지 않는다.")
  @Test
  void findPosts_isLastPage() {
    post(MEET_AT);

    PostSlice slice = companionPostQueryService.findPosts(query(null, null, 20));

    assertThat(slice.hasNext()).isFalse();
    assertThat(slice.nextCursor()).isNull();
  }

  @DisplayName("빈 목록도 커서 없이 정상이다.")
  @Test
  void findPosts_isEmpty() {
    PostSlice slice = companionPostQueryService.findPosts(query(null, null, 20));

    assertThat(slice.posts()).isEmpty();
    assertThat(slice.hasNext()).isFalse();
    assertThat(slice.nextCursor()).isNull();
  }

  /** 목록은 본문을 그대로 싣지 않는다. 자르는 것은 목록 응답의 일이고 여기는 본문 전체를 담는다. */
  @DisplayName("목록도 본문 전체를 들고 나온다.")
  @Test
  void findPosts_hasFullContent() {
    aCompanionPost().meetAt(MEET_AT).content("가".repeat(500)).insert(jdbc);

    PostSlice slice = companionPostQueryService.findPosts(query(null, null, 20));

    assertThat(slice.posts().get(0).content()).hasSize(500);
  }

  @DisplayName("행사를 고르지 않은 글은 행사명과 이미지가 없다.")
  @Test
  void findPosts_hasNoEvent() {
    post(MEET_AT);

    PostView view = companionPostQueryService.findPosts(query(null, null, 20)).posts().get(0);

    assertThat(view.eventId()).isNull();
    assertThat(view.eventTitle()).isNull();
    assertThat(view.eventImageUrl()).isNull();
  }

  /** 원본 시각을 그대로 내리면 특정인의 활동 패턴이 추적된다 (도메인 7.2). */
  @DisplayName("방장의 최근 접속은 구간 값으로만 나온다.")
  @Test
  void findPosts_reducesLastSeen() {
    jdbc.update("UPDATE user SET last_seen_at = ? WHERE id = ?", NOW.minusDays(2), HOST_ID);
    post(MEET_AT);

    PostView view = companionPostQueryService.findPosts(query(null, null, 20)).posts().get(0);

    assertThat(view.lastSeen()).isEqualTo(LastSeen.WITHIN_3_DAYS);
  }

  @DisplayName("한 번도 관측되지 않은 방장은 최근 접속이 없다.")
  @Test
  void findPosts_hasNoLastSeen() {
    jdbc.update("UPDATE user SET last_seen_at = NULL WHERE id = ?", HOST_ID);
    post(MEET_AT);

    PostView view = companionPostQueryService.findPosts(query(null, null, 20)).posts().get(0);

    assertThat(view.lastSeen()).isNull();
  }

  /** 닉네임이 {@code NULL} 이 될 뿐이면 목록에 이름 없는 작성자로 뜬다 (AU-11 「닉네임 익명화」). */
  @DisplayName("탈퇴한 방장의 닉네임은 자리표시자로 나온다.")
  @Test
  void findPosts_anonymizesWithdrawnHost() {
    withdraw(HOST_ID);
    post(MEET_AT);

    PostView view = companionPostQueryService.findPosts(query(null, null, 20)).posts().get(0);

    assertThat(view.nickname()).isEqualTo("탈퇴한 회원");
  }

  /**
   * 사진이 이름보다 더 식별적이고, 최근 접속을 남기면 <b>「탈퇴한 회원 · 오늘 접속」</b> 이 뜬다 — {@code last_seen_at} 이 탈퇴 시점 값으로 남기
   * 때문이다. 그 함정을 그대로 재현하려고 방금 접속한 값을 넣어 둔다.
   */
  @DisplayName("탈퇴한 방장의 프로필 이미지와 최근 접속은 나오지 않는다.")
  @Test
  void findPosts_hidesWithdrawnHostTrace() {
    jdbc.update("UPDATE user SET last_seen_at = ? WHERE id = ?", NOW, HOST_ID);
    withdraw(HOST_ID);
    post(MEET_AT);

    PostView view = companionPostQueryService.findPosts(query(null, null, 20)).posts().get(0);

    assertThat(view.profileImageUrl()).isNull();
    assertThat(view.lastSeen()).isNull();
  }

  /** 요구사항이 「자리표시자 유지」이고 결정 D-3 이 모집글 삭제를 두지 않았다. 글이 사라지는 것은 반대 방향이다. */
  @DisplayName("탈퇴한 방장의 글도 전체 목록에는 남는다.")
  @Test
  void findPosts_keepsWithdrawnHostPost() {
    withdraw(HOST_ID);
    long postId = post(MEET_AT);

    PostSlice slice = companionPostQueryService.findPosts(query(null, null, 20));

    assertThat(idsOf(slice)).containsExactly(postId);
  }

  @DisplayName("상세의 방장이 탈퇴했으면 자리표시자로 나온다.")
  @Test
  void findPost_anonymizesWithdrawnHost() {
    withdraw(HOST_ID);
    long postId = post(MEET_AT);

    PostView view = companionPostQueryService.findPost(postId);

    assertThat(view.nickname()).isEqualTo("탈퇴한 회원");
  }

  @DisplayName("댓글 수를 조회 시점에 세어 함께 내린다.")
  @Test
  void findPosts_countsComments() {
    long postId = post(MEET_AT);
    long rootId = aComment().postId(postId).insert(jdbc);
    aComment().postId(postId).parentId(rootId).secret(true).insert(jdbc);

    PostView view = companionPostQueryService.findPosts(query(null, null, 20)).posts().get(0);

    assertThat(view.commentCount()).isEqualTo(2);
  }

  @DisplayName("댓글이 없는 글의 댓글 수는 0 이다.")
  @Test
  void findPosts_hasNoComment() {
    post(MEET_AT);

    PostView view = companionPostQueryService.findPosts(query(null, null, 20)).posts().get(0);

    assertThat(view.commentCount()).isZero();
  }

  /** status 만 받으면 「모집 완료」와 「종료」를 구분할 수 없다 (화면 계약). */
  @DisplayName("마감된 글은 마감 사유를 함께 내린다.")
  @Test
  void findPosts_hasClosedReason() {
    aCompanionPost()
        .meetAt(MEET_AT)
        .status(PostStatus.CLOSED)
        .closedReason(ClosedReason.MEET_TIME_PASSED)
        .insert(jdbc);

    PostView view = companionPostQueryService.findPosts(query(null, null, 20)).posts().get(0);

    assertThat(view.status()).isEqualTo(PostStatus.CLOSED);
    assertThat(view.closedReason()).isEqualTo(ClosedReason.MEET_TIME_PASSED);
  }

  @DisplayName("정원을 넣지 않은 글은 정원이 없다.")
  @Test
  void findPosts_hasNoCapacity() {
    post(MEET_AT);

    assertThat(companionPostQueryService.findPosts(query(null, null, 20)).posts().get(0).capacity())
        .isNull();
  }

  /** 목록은 잘라 싣지만 상세는 전문이다 (PO-11). 자르는 것은 목록 응답의 일이다. */
  @DisplayName("상세는 본문 전체를 담는다.")
  @Test
  void findPost() {
    long postId = aCompanionPost().meetAt(MEET_AT).content("가".repeat(500)).insert(jdbc);

    PostView view = companionPostQueryService.findPost(postId);

    assertThat(view.id()).isEqualTo(postId);
    assertThat(view.content()).hasSize(500);
  }

  /** 도메인 6장이 CLOSED 의 열람을 「가능」으로 정했다. */
  @DisplayName("마감된 글의 상세도 읽힌다.")
  @Test
  void findPost_isClosed() {
    long postId =
        aCompanionPost()
            .meetAt(MEET_AT)
            .status(PostStatus.CLOSED)
            .closedReason(ClosedReason.MANUAL)
            .insert(jdbc);

    PostView view = companionPostQueryService.findPost(postId);

    assertThat(view.status()).isEqualTo(PostStatus.CLOSED);
    assertThat(view.closedReason()).isEqualTo(ClosedReason.MANUAL);
  }

  @DisplayName("상세도 댓글 수를 조회 시점에 세어 담는다.")
  @Test
  void findPost_countsComments() {
    long postId = post(MEET_AT);
    aComment().postId(postId).insert(jdbc);

    assertThat(companionPostQueryService.findPost(postId).commentCount()).isEqualTo(1);
  }

  @DisplayName("없는 모집글의 상세는 POST_NOT_FOUND 다.")
  @Test
  void findPost_isMissing() {
    assertThatThrownBy(() -> companionPostQueryService.findPost(404L))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(PostErrorCode.POST_NOT_FOUND);
  }

  private long post(LocalDateTime meetAt) {
    return aCompanionPost().meetAt(meetAt).hostId(HOST_ID).insert(jdbc);
  }

  /** {@code User.withdraw} 가 남기는 모양 그대로다 — 상태와 시각을 찍고 닉네임 · 사진을 비운다 (AU-11). */
  private void withdraw(long userId) {
    jdbc.update(
        """
        UPDATE user
           SET status = 'WITHDRAWN', withdrawn_at = ?, nickname = NULL, profile_image_url = NULL
         WHERE id = ?
        """,
        NOW,
        userId);
  }

  private static PostListQuery query(PostStatus status, PostCursor cursor, int size) {
    return new PostListQuery(status, cursor, size);
  }

  private static List<Long> idsOf(PostSlice slice) {
    return slice.posts().stream().map(PostView::id).toList();
  }
}
