package com.duckmoim.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 감사 로그의 기록 (AD-05 · I-13).
 *
 * <p>검증 기준이 한 줄이다 — <b>수정·삭제 불가 확인.</b> I-13 의 이중 방어가 「권한 분리」라 DB 제약이 아니고, 그래서 단위 테스트로 판정한다
 * (테스트-코드-컨벤션.md 「테스트 계층」).
 */
class AuditLogTest {

  private static final long ACTOR_ID = 6L;
  private static final long TARGET_ID = 31L;
  private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 4, 1, 0);

  /** 화면-계약.md 「감사 로그」의 다섯이다. 표를 손으로 옮겨 적고 코드로 구하지 않는다. */
  private static final List<AuditKind> KINDS =
      List.of(
          AuditKind.SANCTION,
          AuditKind.RELEASE,
          AuditKind.SECRET_READ,
          AuditKind.BLIND,
          AuditKind.PURGE);

  @DisplayName("kind 다섯 가지가 모두 기록된다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(AuditKind.class)
  void of(AuditKind kind) {
    AuditLog log = AuditLog.of(ACTOR_ID, kind, TARGET_ID, "신고 5 처리 중", AT);

    assertThat(log.getActorUserId()).isEqualTo(ACTOR_ID);
    assertThat(log.getKind()).isEqualTo(kind);
    assertThat(log.getTargetId()).isEqualTo(TARGET_ID);
    assertThat(log.getAt()).isEqualTo(AT);
  }

  @DisplayName("남기는 행위는 다섯뿐이다.")
  @Test
  void kinds() {
    assertThat(AuditKind.values()).containsExactlyElementsOf(KINDS);
  }

  @DisplayName("대상 종류는 행위가 정한다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(AuditKind.class)
  void targetType(AuditKind kind) {
    AuditLog log = AuditLog.of(ACTOR_ID, kind, TARGET_ID, null, AT);

    assertThat(log.getTargetType()).isEqualTo(kind.targetType());
  }

  @DisplayName("제재·해제·파기는 유저를, 열람·블라인드는 댓글을 가리킨다.")
  @Test
  void targetTypeOfEachKind() {
    assertThat(AuditKind.SANCTION.targetType()).isEqualTo(AuditTargetType.USER);
    assertThat(AuditKind.RELEASE.targetType()).isEqualTo(AuditTargetType.USER);
    assertThat(AuditKind.PURGE.targetType()).isEqualTo(AuditTargetType.USER);
    assertThat(AuditKind.SECRET_READ.targetType()).isEqualTo(AuditTargetType.COMMENT);
    assertThat(AuditKind.BLIND.targetType()).isEqualTo(AuditTargetType.COMMENT);
  }

  @DisplayName("상세는 없어도 기록된다.")
  @Test
  void detailIsOptional() {
    AuditLog log = AuditLog.of(ACTOR_ID, AuditKind.BLIND, TARGET_ID, null, AT);

    assertThat(log.getDetail()).isNull();
  }

  @DisplayName("행위자·행위·대상·시각이 없으면 기록되지 않는다.")
  @Test
  void requiresEveryColumn() {
    assertThatThrownBy(() -> AuditLog.of(null, AuditKind.BLIND, TARGET_ID, null, AT))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> AuditLog.of(ACTOR_ID, null, TARGET_ID, null, AT))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> AuditLog.of(ACTOR_ID, AuditKind.BLIND, null, null, AT))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> AuditLog.of(ACTOR_ID, AuditKind.BLIND, TARGET_ID, null, null))
        .isInstanceOf(NullPointerException.class);
  }

  /**
   * I-13 의 「append-only 경로만 제공」이 엔티티에 닿는 자리다.
   *
   * <p>만든 뒤에 바꾸는 문이 하나도 없어야 한다. 세터를 하나 열면 그 문으로 지나간 수정이 기록에 흔적을 남기지 않는다 — 감사 로그가 감사 로그가 아니게 된다.
   */
  @DisplayName("기록을 고치는 메서드가 없다.")
  @Test
  void hasNoMutator() {
    List<String> mutators =
        Arrays.stream(AuditLog.class.getDeclaredMethods())
            .filter(method -> !method.isSynthetic())
            .filter(AuditLogTest::looksLikeMutator)
            .map(Method::getName)
            .toList();

    assertThat(mutators).isEmpty();
  }

  private static boolean looksLikeMutator(Method method) {
    String name = method.getName();
    return name.startsWith("set")
        || name.startsWith("update")
        || name.startsWith("change")
        || name.startsWith("delete");
  }
}
