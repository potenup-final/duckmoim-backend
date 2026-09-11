package com.duckmoim.architecture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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
 *
 * <h2>설정만으로는 모자란다 (STAR-130)</h2>
 *
 * <p><b>{@code ignore = all} 은 git 이 핀을 발견하는 것을 막을 뿐, 이미 인덱스에 들어간 핀은 막지 못한다.</b> {@code git add -f
 * docs/wiki} 와 {@code git update-index} 직접 조작(IDE 의 소스컨트롤 패널이 쓰는 방식)이 그 경로이고, git 문서가 <i>staged 된
 * 경우에는 status · commit 출력에 나타난다</i>고 예외를 명시해 두었다. STAR-76 이 들어온 뒤에도 PR #103 의 커밋 {@code 8c95eac} 에서
 * 재발했다 — 자바 파일 하나만 고친 커밋에 핀이 함께 실렸다.
 *
 * <p>그래서 <b>선언 검사에 상태 검사를 더한다.</b> 커밋 순간을 막는 것은 {@code .githooks/pre-commit} 이고, 여기는 그 훅이 없는 클론과
 * {@code --no-verify} 로 건너뛴 경우를 받는다.
 */
class SubmodulePinTest {

  private static final Path GITMODULES = Path.of(".gitmodules");

  private static final String WIKI_PATH = "docs/wiki";

  private static final String WIKI_SECTION = "[submodule \"" + WIKI_PATH + "\"]";

  /** git 이 gitlink 항목에 쓰는 모드. 이 값이어야 서브모듈 포인터다. */
  private static final String GITLINK_MODE = "160000";

  @DisplayName("위키 서브모듈은 핀이 커밋에 실리지 않게 선언된다.")
  @Test
  void wikiSubmoduleIgnoresPin() {
    List<String> section = wikiSection();

    assertThat(section)
        .as(".gitmodules 의 docs/wiki 절에 `ignore = all` 이 있어야 한다 (STAR-76)")
        .anyMatch(SubmodulePinTest::declaresIgnoreAll);
  }

  @DisplayName("위키 서브모듈 핀이 인덱스에 실려 있지 않다.")
  @Test
  void wikiSubmodulePinIsNotStaged() {
    assertThat(stagedPinMismatch(Path.of(".")))
        .as("인덱스의 위키 핀이 HEAD 와 달라졌다. `git restore --staged %s` 로 뺀다 (STAR-130)", WIKI_PATH)
        .isEmpty();
  }

  /**
   * 인덱스의 위키 핀이 HEAD 와 어긋났으면 그 사실을 적어 돌려준다.
   *
   * <p><b>{@code git diff --cached} 를 쓰지 않는다.</b> {@code ignore = all} 이 diff 까지 가려서, 핀이 스테이징된
   * 상태에서도 출력이 <b>비어 있다</b> — 그렇게 짠 검사는 조용히 통과한다. {@code --ignore-submodules=none} 을 붙이면 보이지만, 붙이는
   * 것을 잊으면 아무 신호가 없으므로 인덱스와 HEAD 의 gitlink 를 직접 비교한다. git 2.54.0 에서 실측했다.
   *
   * <p><b>{@link RulesAreAliveTest} 가 이 메서드를 임시 저장소에 겨눠 생존을 증명한다.</b> 그래서 검사할 저장소를 인자로 받는다 — 여기서
   * 프로세스 작업 디렉터리를 고정하면 픽스처를 만들 수 없다.
   *
   * @return 어긋났으면 설명, 아니면 빈 값. 비교할 HEAD 항목이 없는 트리(최초 커밋 · 서브모듈이 들어오기 전에 갈라진 브랜치)도 빈 값이다
   */
  static Optional<String> stagedPinMismatch(Path repoRoot) {
    Optional<String> headPin =
        git(repoRoot, "rev-parse", "--verify", "--quiet", "HEAD:" + WIKI_PATH);
    if (headPin.isEmpty()) {
      return Optional.empty();
    }

    Optional<String> stagedPin = stagedPin(repoRoot);
    if (stagedPin.isEmpty() || stagedPin.get().equals(headPin.get())) {
      return Optional.empty();
    }

    return Optional.of("HEAD %s, 인덱스 %s".formatted(headPin.get(), stagedPin.get()));
  }

  /** 인덱스에 올라온 위키 gitlink. 없으면 빈 값이고, 그것이 평상시의 모습이다. */
  private static Optional<String> stagedPin(Path repoRoot) {
    return git(repoRoot, "ls-files", "--stage", "--", WIKI_PATH)
        .map(line -> line.split("\\s+"))
        .filter(fields -> fields.length >= 2 && fields[0].equals(GITLINK_MODE))
        .map(fields -> fields[1]);
  }

  /**
   * git 을 돌려 첫 줄을 받는다. 종료 코드가 0 이 아니면 빈 값이다 — 「그런 항목이 없다」와 같은 뜻으로 쓴다.
   *
   * <p>git 이 없거나 저장소가 아니면 <b>건너뛴다.</b> 실패로 만들면 소스 아카이브로 받은 트리에서 빌드가 깨진다.
   */
  private static Optional<String> git(Path repoRoot, String... args) {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(args));

    try {
      Process process =
          new ProcessBuilder(command)
              .directory(repoRoot.toFile())
              .redirectErrorStream(false)
              .start();

      String firstLine;
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8))) {
        firstLine = reader.readLine();
      }

      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        abort("git 이 응답하지 않아 핀 검사를 건너뛴다.");
      }

      if (process.exitValue() != 0 || firstLine == null || firstLine.isBlank()) {
        return Optional.empty();
      }

      return Optional.of(firstLine.strip());

    } catch (IOException e) {
      return abort("git 을 실행할 수 없어 핀 검사를 건너뛴다: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return abort("핀 검사가 중단됐다.");
    }
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
