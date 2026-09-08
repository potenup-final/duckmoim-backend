package com.duckmoim.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 위키 서브모듈의 <b>핀이 커밋에 실리지 않는지</b> 검사한다 (STAR-76).
 *
 * <p>이 저장소는 위키 핀을 관리하지 않는다. 세션 시작 훅과 {@code syncWiki} 가 매번 {@code --remote} 로 최신을 당기므로 <b>핀에 담긴 정보가
 * 실제로 쓰이지 않는다.</b> 그런데도 핀이 커밋에 실리면 브랜치마다 값이 갈리고, 그때 PR 이 막힌다.
 *
 * <p><b>막히는 방식이 고약하다.</b> 로컬 머지는 위키 저장소가 옆에 있어 두 핀의 조상 관계를 따져 자동으로 해소한다 — {@code Automatic merge
 * went well} 이 나온다. GitHub 은 PRIVATE 서브모듈의 객체가 없어 그 판정을 못 해서 머지 버튼을 잠근다. <b>코드 충돌은 0인데 PR 이 {@code
 * CONFLICTING} 이고, 이유가 diff 에 안 보인다.</b> STAR-55 의 PR #45 가 그랬다.
 *
 * <h2>{@code ignore} 가 {@code .gitmodules} 에 있어야 하는 이유</h2>
 *
 * <p>같은 {@code ignore = all} 인데 <b>어디에 적느냐로 동작이 갈린다.</b> 격리된 서브모듈 저장소에 핀을 갈라 놓고 대조했다 (git 2.54.0).
 *
 * <table>
 *   <caption>설정 위치별 동작</caption>
 *   <tr><th>설정 위치<th>{@code git status}<th>{@code git add -A}
 *   <tr><td>없음<td>{@code M docs/wiki}<td><b>핀 실림</b>
 *   <tr><td>로컬 config<td>깨끗<td><b>핀 실림</b>
 *   <tr><td>{@code .gitmodules}<td>깨끗<td>안 실림
 *   <tr><td>둘 다<td>깨끗<td>안 실림
 * </table>
 *
 * <p>로컬 config 는 <b>보여주는 것만</b> 막는다. 그래서 작업 트리가 깨끗해 보이는 채로 {@code git add -A} 가 핀을 집어 갔고, 이 버그가 오래
 * 살아남았다. {@code configureWikiIgnore} 태스크가 심는 로컬 config 는 이제 중복이고, 실제로 막는 것은 이 파일의 한 줄이다.
 *
 * <p><b>그 한 줄을 이 검사가 지킨다.</b> 지우기 쉽고, 지워도 다음에 누가 핀을 실을 때까지 아무 신호가 없다 — 그때는 이미 develop 에 들어가 있다.
 */
class SubmodulePinTest {

  private static final Path GITMODULES = Path.of(".gitmodules");

  private static final String WIKI_SECTION = "[submodule \"docs/wiki\"]";

  @DisplayName("위키 서브모듈은 핀이 커밋에 실리지 않게 선언된다.")
  @Test
  void wikiSubmoduleIgnoresPin() {
    List<String> section = wikiSection();

    assertThat(section)
        .as(".gitmodules 의 docs/wiki 절에 `ignore = all` 이 있어야 한다 (STAR-76)")
        .anyMatch(SubmodulePinTest::declaresIgnoreAll);
  }

  /**
   * {@code .gitmodules} 가 없으면 건너뛴다.
   *
   * <p><b>건너뛴 사실을 남긴다</b> — 검사하지 않은 것과 통과한 것이 같아 보이면 안 된다. {@link WikiReferenceTest} 와 같은 판단이다.
   * 서브모듈을 선언하지 않은 트리(하네스 머지 전에 갈라진 브랜치)가 실재한다.
   */
  private static List<String> wikiSection() {
    if (!Files.exists(GITMODULES)) {
      abort(".gitmodules 가 없어 건너뛴다. 이 브랜치에는 위키 서브모듈이 없다.");
    }

    List<String> lines = read(GITMODULES);
    int start = lines.indexOf(WIKI_SECTION);

    assertThat(start).as(".gitmodules 에 %s 절이 있어야 한다", WIKI_SECTION).isNotNegative();

    int end = start + 1;
    while (end < lines.size() && !lines.get(end).stripLeading().startsWith("[")) {
      end++;
    }

    return lines.subList(start + 1, end);
  }

  /** 공백과 들여쓰기를 견딘다. git 이 쓰면 탭, 사람이 쓰면 스페이스라 형태가 갈린다. */
  private static boolean declaresIgnoreAll(String line) {
    String[] parts = line.split("=", 2);
    if (parts.length != 2) {
      return false;
    }

    return parts[0].strip().equals("ignore") && parts[1].strip().equals("all");
  }

  private static List<String> read(Path path) {
    try {
      return Files.readAllLines(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
