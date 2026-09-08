package com.duckmoim.companion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 액션 버튼 전 조합 (CM-18).
 *
 * <p>요청자 4종 × 위치 2 × 상태 3 = <b>24 조합</b>을 네 테스트가 나눠 덮는다. 테스트 컨벤션이 권한 판정에 모든 조합을 요구한다.
 *
 * <p><b>검증 기준이 「본인 댓글에 신고 버튼 미노출」이다.</b> 자기 글을 자기가 신고하는 것은 실수 아니면 장난이고, 백오피스 큐만 더럽힌다.
 *
 * <p>{@link CommentVisibilityPolicyTest} 와 같이 기대값을 계산하지 않고 선언한다.
 */
class CommentActionPolicyTest {

  private static final long AUTHOR_ID = 1L;
  private static final long HOST_ID = 2L;
  private static final long STRANGER_ID = 3L;
  private static final long PARENT_AUTHOR_ID = 4L;

  private final CommentActionPolicy policy = new CommentActionPolicy();

  private enum Requester {
    AUTHOR(AUTHOR_ID),
    HOST(HOST_ID),
    STRANGER(STRANGER_ID),
    GUEST(null);

    private final Long id;

    Requester(Long id) {
      this.id = id;
    }
  }

  private enum Position {
    ROOT(null),
    REPLY(PARENT_AUTHOR_ID);

    private final Long parentAuthorId;

    Position(Long parentAuthorId) {
      this.parentAuthorId = parentAuthorId;
    }
  }

  @DisplayName("자리표시자에는 아무 버튼도 붙지 않는다.")
  @ParameterizedTest(name = "{0} · {1} · {2}")
  @MethodSource("inactiveCombinations")
  void availableActions_whenNotActive(
      Requester requester, Position position, CommentStatus status) {

    assertThat(policy.availableActions(target(status), context(requester, position))).isEmpty();
  }

  @DisplayName("비회원에게는 아무 버튼도 붙지 않는다.")
  @ParameterizedTest(name = "{0}")
  @MethodSource("everyPosition")
  void availableActions_whenGuest(Position position) {
    assertThat(
            policy.availableActions(
                target(CommentStatus.ACTIVE), context(Requester.GUEST, position)))
        .isEmpty();
  }

  @DisplayName("루트 댓글의 버튼은 요청자에 따라 갈린다.")
  @ParameterizedTest(name = "{0} → {1}")
  @MethodSource("rootExpectations")
  void availableActions_onRoot(Requester requester, List<CommentAvailableAction> expected) {
    assertThat(
            policy.availableActions(
                target(CommentStatus.ACTIVE), context(requester, Position.ROOT)))
        .containsExactlyElementsOf(expected);
  }

  /** 대댓글에는 REPLY 가 없다 — 깊이 1단계 고정 (CM-02). */
  @DisplayName("대댓글에는 답글 버튼이 붙지 않는다.")
  @ParameterizedTest(name = "{0} → {1}")
  @MethodSource("replyExpectations")
  void availableActions_onReply(Requester requester, List<CommentAvailableAction> expected) {
    assertThat(
            policy.availableActions(
                target(CommentStatus.ACTIVE), context(requester, Position.REPLY)))
        .containsExactlyElementsOf(expected);
  }

  /** 16 조합. */
  private static Stream<Arguments> inactiveCombinations() {
    return Arrays.stream(Requester.values())
        .flatMap(
            requester ->
                Arrays.stream(Position.values())
                    .flatMap(
                        position ->
                            Stream.of(CommentStatus.DELETED, CommentStatus.BLINDED)
                                .map(status -> Arguments.of(requester, position, status))));
  }

  /** 2 조합. */
  private static Stream<Arguments> everyPosition() {
    return Arrays.stream(Position.values()).map(Arguments::of);
  }

  /** 3 조합. */
  private static Stream<Arguments> rootExpectations() {
    return Stream.of(
        Arguments.of(
            Requester.AUTHOR,
            List.of(
                CommentAvailableAction.REPLY,
                CommentAvailableAction.EDIT,
                CommentAvailableAction.DELETE)),
        Arguments.of(
            Requester.HOST,
            List.of(
                CommentAvailableAction.REPLY,
                CommentAvailableAction.DELETE,
                CommentAvailableAction.REPORT)),
        Arguments.of(
            Requester.STRANGER,
            List.of(CommentAvailableAction.REPLY, CommentAvailableAction.REPORT)));
  }

  /** 3 조합. */
  private static Stream<Arguments> replyExpectations() {
    return Stream.of(
        Arguments.of(
            Requester.AUTHOR, List.of(CommentAvailableAction.EDIT, CommentAvailableAction.DELETE)),
        Arguments.of(
            Requester.HOST, List.of(CommentAvailableAction.DELETE, CommentAvailableAction.REPORT)),
        Arguments.of(Requester.STRANGER, List.of(CommentAvailableAction.REPORT)));
  }

  private static CommentReadTarget target(CommentStatus status) {
    return new CommentReadTarget(AUTHOR_ID, false, status);
  }

  private static CommentReadContext context(Requester requester, Position position) {
    return new CommentReadContext(requester.id, HOST_ID, position.parentAuthorId);
  }
}
