package com.duckmoim.admin.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * I-13 「감사 로그는 수정·삭제되지 않는다」의 검증 기준이다 — 명세서 AD-05 의 <b>「수정·삭제 불가 확인」</b>.
 *
 * <p>검증 위치가 「append-only 경로만 제공」이고 이중 방어가 「권한 분리」다. DB 제약이 아니므로 단위 테스트로 판정한다 (테스트-코드-컨벤션.md 「테스트
 * 계층」).
 *
 * <p><b>이 검사가 없으면 초록불이 아무것도 증명하지 않는다.</b> 지금은 지우는 코드를 아무도 쓰지 않아서 지워지지 않는 것이지, 지울 수 없어서가 아니다. {@code
 * AuditLogRepository} 가 {@code JpaRepository} 를 상속하는 한 줄짜리 변경으로 {@code deleteById} 가 열리고, 그때 아무 검사도
 * 빨간불이 되지 않는다. 여기가 그 한 줄을 잡는다.
 */
class AuditLogAppendOnlyTest {

  /** Spring Data 가 이름으로 파생시키거나 기본 제공하는 쓰기 경로들이다. */
  private static final List<String> FORBIDDEN_PREFIXES =
      List.of("delete", "remove", "update", "flush");

  @DisplayName("저장소는 지우는 경로를 제공하지 않는다.")
  @Test
  void repositoryHasNoDeletePath() {
    assertThat(writeMethodsOtherThanSave()).isEmpty();
  }

  @DisplayName("저장소가 여는 쓰기는 save 하나다.")
  @Test
  void repositoryOpensOnlySave() {
    List<String> declared =
        Arrays.stream(AuditLogRepository.class.getMethods())
            .map(Method::getName)
            .filter(name -> !name.equals("findSlice"))
            .distinct()
            .toList();

    assertThat(declared).containsExactly("save");
  }

  private static List<String> writeMethodsOtherThanSave() {
    return Arrays.stream(AuditLogRepository.class.getMethods())
        .map(Method::getName)
        .filter(AuditLogAppendOnlyTest::isForbidden)
        .distinct()
        .toList();
  }

  private static boolean isForbidden(String name) {
    return FORBIDDEN_PREFIXES.stream().anyMatch(name::startsWith);
  }
}
