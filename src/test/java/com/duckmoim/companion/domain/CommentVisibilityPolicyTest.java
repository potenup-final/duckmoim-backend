package com.duckmoim.companion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 가시성 매트릭스 전 조합 (CM-04 의 검증 기준이 「전 조합 테스트 통과」다).
 *
 * <p>요청자 5종 × 위치 2 × 공개 여부 2 × 상태 3 = <b>60 조합</b>을 네 테스트가 나눠 덮는다. 테스트 컨벤션이 <i>"권한 판정이 걸리면 모든 조합을
 * 낸다. 대표 케이스만 고르지 않는다"</i> 고 정했다.
 *
 * <p><b>기대값을 계산하지 않고 선언한다.</b> 조합마다 기대값을 코드로 구하면 판정 로직을 테스트에 한 번 더 쓰는 것이고, 둘이 같이 틀리면 초록불이 난다. 그래서
 * 「전부 false」 · 「전부 true」로 갈리는 두 축은 조합만 생성하고, 사람마다 갈리는 두 축은 짝을 손으로 적는다.
 *
 * <p>{@code I-07} 은 이중 방어가 없다. DB 제약이 아니므로 단위 테스트가 유일한 방어선이다.
 */
class CommentVisibilityPolicyTest {

  private static final long AUTHOR_ID = 1L;
  private static final long HOST_ID = 2L;
  private static final long PARENT_AUTHOR_ID = 3L;
  private static final long STRANGER_ID = 4L;

  private final CommentVisibilityPolicy policy = new CommentVisibilityPolicy();

  /** 판정이 갈리는 다섯 사람. 비회원은 요청자 ID 가 없다 (CM-20). */
  private enum Requester {
    AUTHOR(AUTHOR_ID),
    HOST(HOST_ID),
    PARENT_AUTHOR(PARENT_AUTHOR_ID),
    STRANGER(STRANGER_ID),
    GUEST(null);

    private final Long id;

    Requester(Long id) {
      this.id = id;
    }
  }

  /** 루트 댓글에는 부모가 없으므로 부모 작성자도 없다. */
  private enum Position {
    ROOT(null),
    REPLY(PARENT_AUTHOR_ID);

    private final Long parentAuthorId;

    Position(Long parentAuthorId) {
      this.parentAuthorId = parentAuthorId;
    }
  }

  @DisplayName("삭제되거나 블라인드된 댓글은 누구에게도 본문을 보여주지 않는다.")
  @ParameterizedTest(name = "{0} · {1} · secret={2} · {3}")
  @MethodSource("inactiveCombinations")
  void canReadContent_whenNotActive(
      Requester requester, Position position, boolean secret, CommentStatus status) {

    boolean readable = policy.canReadContent(target(secret, status), context(requester, position));

    assertThat(readable).isFalse();
  }

  @DisplayName("공개 댓글은 비회원까지 누구나 본문을 본다.")
  @ParameterizedTest(name = "{0} · {1}")
  @MethodSource("everyRequesterAndPosition")
  void canReadContent_whenPublic(Requester requester, Position position) {
    boolean readable =
        policy.canReadContent(target(false, CommentStatus.ACTIVE), context(requester, position));

    assertThat(readable).isTrue();
  }

  @DisplayName("비밀 루트 댓글은 작성자와 방장만 본문을 본다.")
  @ParameterizedTest(name = "{0} → {1}")
  @MethodSource("secretRootExpectations")
  void canReadContent_whenSecretRoot(Requester requester, boolean expected) {
    boolean readable =
        policy.canReadContent(
            target(true, CommentStatus.ACTIVE), context(requester, Position.ROOT));

    assertThat(readable).isEqualTo(expected);
  }

  @DisplayName("비밀 대댓글은 작성자 · 방장 · 부모 댓글 작성자가 본문을 본다.")
  @ParameterizedTest(name = "{0} → {1}")
  @MethodSource("secretReplyExpectations")
  void canReadContent_whenSecretReply(Requester requester, boolean expected) {
    boolean readable =
        policy.canReadContent(
            target(true, CommentStatus.ACTIVE), context(requester, Position.REPLY));

    assertThat(readable).isEqualTo(expected);
  }

  /** 40 조합. 상태가 ACTIVE 가 아니면 나머지 축이 무엇이든 false 다. */
  private static Stream<Arguments> inactiveCombinations() {
    return Arrays.stream(Requester.values())
        .flatMap(
            requester ->
                Arrays.stream(Position.values())
                    .flatMap(
                        position ->
                            Stream.of(true, false)
                                .flatMap(
                                    secret ->
                                        Stream.of(CommentStatus.DELETED, CommentStatus.BLINDED)
                                            .map(
                                                status ->
                                                    Arguments.of(
                                                        requester, position, secret, status)))));
  }

  /** 10 조합. */
  private static Stream<Arguments> everyRequesterAndPosition() {
    return Arrays.stream(Requester.values())
        .flatMap(
            requester ->
                Arrays.stream(Position.values())
                    .map(position -> Arguments.of(requester, position)));
  }

  /** 5 조합. 7.1 「비밀 루트 댓글 — 작성자 본인, 방장」 행을 그대로 적었다. */
  private static Stream<Arguments> secretRootExpectations() {
    return Stream.of(
        Arguments.of(Requester.AUTHOR, true),
        Arguments.of(Requester.HOST, true),
        Arguments.of(Requester.PARENT_AUTHOR, false),
        Arguments.of(Requester.STRANGER, false),
        Arguments.of(Requester.GUEST, false));
  }

  /** 5 조합. 7.1 「비밀 대댓글 — 작성자 본인, 방장, 부모 댓글 작성자」 행을 그대로 적었다. */
  private static Stream<Arguments> secretReplyExpectations() {
    return Stream.of(
        Arguments.of(Requester.AUTHOR, true),
        Arguments.of(Requester.HOST, true),
        Arguments.of(Requester.PARENT_AUTHOR, true),
        Arguments.of(Requester.STRANGER, false),
        Arguments.of(Requester.GUEST, false));
  }

  private static CommentReadTarget target(boolean secret, CommentStatus status) {
    return new CommentReadTarget(AUTHOR_ID, secret, status);
  }

  private static CommentReadContext context(Requester requester, Position position) {
    return new CommentReadContext(requester.id, HOST_ID, position.parentAuthorId);
  }
}
