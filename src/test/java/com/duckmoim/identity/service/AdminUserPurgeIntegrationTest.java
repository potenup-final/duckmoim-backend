package com.duckmoim.identity.service;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.companion.domain.CommentListQuery;
import com.duckmoim.companion.service.CommentQueryService;
import com.duckmoim.companion.service.CommentView;
import com.duckmoim.identity.domain.AuthorDisplay;
import com.duckmoim.identity.infra.UserRepository;
import com.duckmoim.notification.domain.PushSubscription;
import com.duckmoim.notification.infra.PushSubscriptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파기가 다른 컨텍스트에 남기는 결과 (AD-05).
 *
 * <p>{@link AdminUserPurgeServiceTest} 는 회원 행과 장부만 본다. 여기서는 <b>행을 남기기로 한 결정이 실제로 지켜지는지</b>와 탈퇴 기록을
 * 듣는 쪽이 파기에도 반응하는지를 본다 — 둘 다 이 티켓이 정한 것이고, 틀리면 다른 팀의 화면이 깨진다.
 */
@SpringBootTest
@Transactional
class AdminUserPurgeIntegrationTest {

  private static final long ADMIN_ID = 6L;
  private static final String REASON = "나이 확인 요청에 30일간 답이 없었습니다";

  @Autowired private AdminUserPurgeService adminUserPurgeService;
  @Autowired private CommentQueryService commentQueryService;
  @Autowired private UserRepository userRepository;
  @Autowired private PushSubscriptionRepository pushSubscriptionRepository;
  @Autowired private JdbcTemplate jdbc;

  /**
   * <b>행을 지우지 않기로 한 결정이 여기서 증명된다.</b> 작성자 블록이 {@code user} 행을 내부 조인으로 읽으므로, 파기가 행을 지우면 이 댓글이 목록에서
   * 통째로 빠진다 — 지운 것도 아닌데 사라지고, 매달린 대댓글이 고아가 된다 (CM-11).
   */
  @DisplayName("파기한 회원의 댓글은 자리표시자로 목록에 남는다.")
  @Test
  void purgeKeepsCommentWithPlaceholder() {
    long userId = aUser().nickname("파기될덕후").insert(jdbc);
    long postId = aCompanionPost().insert(jdbc);
    aComment().postId(postId).authorId(userId).insert(jdbc);

    adminUserPurgeService.purge(userId, ADMIN_ID, REASON);

    CommentView view =
        commentQueryService.findComments(new CommentListQuery(postId, null, 20)).roots().get(0);
    assertThat(view.nickname()).isEqualTo(AuthorDisplay.WITHDRAWN_NICKNAME);
  }

  /**
   * <b>지금 이렇게 동작한다는 것만 고정한다. 옳다고 주장하지 않는다.</b>
   *
   * <p>회원번호를 비우면 카카오 로그인의 조회에 아무것도 안 걸려 (AU-01) 같은 카카오 계정이 <b>새 계정</b>으로 들어온다. 제재는 {@code
   * sanction.user_id} 에 붙어 있어 새 회원번호에는 따라오지 않으므로, <b>{@code BANNED} 회원을 파기하면 제재가 풀린다.</b> 스스로 탈퇴한
   * 사람은 {@code AuthService.lockUser} 가 거절해 돌아오지 못하는데 관리자가 파기한 사람은 돌아오는, 방향이 뒤집힌 자리다.
   *
   * <p>막는 수단은 회원번호의 <b>원문 대신 대조용 해시</b>를 제재 기록 기간만큼 남기는 것이고, 처리방침 제3조가 그 보유 기간을 이미 적어 두었다. 이 티켓의 범위
   * 밖이라 여기서는 현재 동작만 못박는다 — 막는 쪽이 들어오면 이 검사가 빨간불이 되어 자리를 가리킨다.
   */
  @DisplayName("파기하면 그 카카오 회원번호로는 계정을 찾을 수 없다. 재가입을 막지 않는다.")
  @Test
  void purgeReleasesKakaoUserId() {
    long kakaoUserId = 987_654_321L;
    long userId = aUser().kakaoUserId(kakaoUserId).insert(jdbc);

    adminUserPurgeService.purge(userId, ADMIN_ID, REASON);

    assertThat(userRepository.findByKakaoUserId(kakaoUserId)).isEmpty();
    assertThat(userRepository.findById(userId)).isPresent();
  }

  /**
   * <b>탈퇴 없이 바로 파기된 계정이 이 검사의 자리다.</b> 푸시는 로그인 없이 기기에 직접 닿아서, 구독이 남으면 파기된 사람의 폰에 알림이 뜬다 ({@code
   * UserWithdrawn} 의 javadoc 이 같은 이유를 적어 두었다).
   */
  @DisplayName("파기하면 그 회원의 푸시 구독이 지워진다.")
  @Test
  void purgeForgetsPushSubscription() {
    long userId = aUser().insert(jdbc);
    pushSubscriptionRepository.save(
        PushSubscription.of(userId, "https://fcm.googleapis.com/fcm/send/파기대상", "key", "auth"));

    adminUserPurgeService.purge(userId, ADMIN_ID, REASON);

    assertThat(pushSubscriptionRepository.findByUserIdOrderByIdAsc(userId)).isEmpty();
  }
}
