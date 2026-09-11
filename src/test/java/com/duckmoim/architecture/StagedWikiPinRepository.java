package com.duckmoim.architecture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 위키 핀이 인덱스에 실린 git 저장소를 만든다 (STAR-130). 핀 게이트의 <b>위반 픽스처</b>다.
 *
 * <p><b>왜 클래스가 아니라 저장소인가.</b> 다른 픽스처는 규칙을 어기는 자바 클래스지만, 핀 게이트가 보는 것은 코드가 아니라 git 인덱스의 상태다. 어기는 상태를
 * 만들려면 저장소가 있어야 한다.
 *
 * <p><b>이 저장소 안에서만 어긴다.</b> 검사 대상을 인자로 받는 {@code SubmodulePinTest#stagedPinMismatch} 와 {@code
 * core.hooksPath} 덕분에, 진짜 작업 트리를 더럽히지 않고 위반을 만들 수 있다. 네트워크도 쓰지 않는다 — 서브모듈을 실제로 받아오지 않고 gitlink 항목만
 * 만든다.
 *
 * <p>핀 값이 실재하는 커밋일 필요는 없다. 게이트가 두 SHA 를 <b>비교</b>만 하고 객체를 찾지 않기 때문이고, 실재하는 값을 쓰려면 위키를 받아와야 해서 검사가
 * 네트워크에 묶인다.
 */
final class StagedWikiPinRepository {

  /** HEAD 에 적힌 핀. */
  private static final String COMMITTED_PIN = "1111111111111111111111111111111111111111";

  /** 인덱스에 실린 핀. 위와 달라야 위반이 된다. */
  private static final String STAGED_PIN = "2222222222222222222222222222222222222222";

  /** 핀과 함께 커밋되는 평범한 변경. 재발한 커밋이 자바 파일 하나짜리였다. */
  private static final String CODE_FILE = "Code.java";

  private static final String GITMODULES =
      """
      [submodule "docs/wiki"]
      \tpath = docs/wiki
      \turl = https://example.invalid/duckmoim-wiki.git
      \tignore = all
      """;

  private StagedWikiPinRepository() {}

  /**
   * 핀이 어긋난 저장소를 만들어 그 경로를 돌려준다.
   *
   * <p><b>재발한 커밋의 모양 그대로다.</b> 자바 파일 하나를 고친 평범한 커밋에 핀이 딸려 온 것이 PR #103 의 {@code 8c95eac} 였다. 그래서
   * 인덱스에 코드 변경과 어긋난 핀이 함께 올라와 있다.
   *
   * <p><b>코드 변경을 함께 두는 것이 검사의 조건이다.</b> 핀만 올려 두면 핀을 뺐을 때 인덱스가 비어 {@code git commit} 이 「nothing to
   * commit」으로 1을 내고, 그러면 훅이 막은 것과 구분되지 않는다 — 통과를 확인하는 검사가 훅과 무관한 이유로 빨간불이 된다.
   *
   * <p>기준 커밋을 {@code --no-verify} 로 남기는 것은 <b>픽스처를 세우는 커밋</b>이라서다. 이 커밋까지 훅에 걸리면 픽스처를 만들 수 없고, 이
   * 시점에는 아직 핀이 어긋나지도 않았다.
   */
  static Path create(Path parent) {
    Path repo = parent.resolve("staged-wiki-pin");

    write(repo, ".gitmodules", GITMODULES);
    write(repo, CODE_FILE, "class Code {}\n");

    gitOrSkip(repo, "init", "--quiet", "--initial-branch=main", ".");
    gitOrSkip(repo, "config", "user.email", "gate@duckmoim.test");
    gitOrSkip(repo, "config", "user.name", "duckmoim gate");
    gitOrSkip(repo, "config", "commit.gpgsign", "false");
    gitOrSkip(repo, "add", ".gitmodules", CODE_FILE);
    gitOrSkip(repo, "update-index", "--add", "--cacheinfo", gitlink(COMMITTED_PIN));
    gitOrSkip(repo, "commit", "--quiet", "--no-verify", "-m", "base");

    write(repo, CODE_FILE, "class Code {\n  void added() {}\n}\n");
    gitOrSkip(repo, "add", CODE_FILE);
    gitOrSkip(repo, "update-index", "--cacheinfo", gitlink(STAGED_PIN));

    return repo;
  }

  /**
   * 인덱스에서 핀만 뺀다. 게이트가 <b>과하게 잡지 않는지</b> 보는 자리다.
   *
   * <p>코드 변경은 인덱스에 그대로 남는다 — 그래야 이어지는 커밋이 「보낼 것이 없어서」가 아니라 <b>훅이 통과시켜서</b> 성공한다.
   */
  static void unstagePin(Path repo) {
    gitOrSkip(repo, "restore", "--staged", "docs/wiki");
  }

  /** git 을 돌리고 종료 코드와 출력을 그대로 돌려준다. 실패를 기대하는 호출이 있어 여기서는 판정하지 않는다. */
  static Execution git(Path repo, String... args) {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(args));

    try {
      Process process =
          new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();

      String output = new String(process.getInputStream().readAllBytes(), UTF_8);

      if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return abort("git 이 응답하지 않아 픽스처를 만들지 못했다.");
      }

      return new Execution(process.exitValue(), output);

    } catch (IOException e) {
      return abort("git 을 실행할 수 없어 픽스처를 만들지 못했다: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return abort("픽스처를 만드는 중에 중단됐다.");
    }
  }

  /**
   * 픽스처를 세우는 명령. 실패하면 <b>건너뛴다.</b>
   *
   * <p>실패로 만들지 않는 것은 그것이 게이트의 실패가 아니라 환경의 실패이기 때문이다 — git 판본이 낮아 {@code --initial-branch} 를 모르는 경우가
   * 그렇다. 다만 건너뛴 사실은 남는다.
   */
  private static void gitOrSkip(Path repo, String... args) {
    Execution execution = git(repo, args);

    if (execution.exitCode() != 0) {
      abort("픽스처를 세우지 못해 건너뛴다: git %s → %s".formatted(String.join(" ", args), execution.output()));
    }
  }

  private static String gitlink(String pin) {
    return "160000," + pin + ",docs/wiki";
  }

  private static void write(Path repo, String name, String content) {
    try {
      Files.createDirectories(repo);
      Files.writeString(repo.resolve(name), content, UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 돌린 git 한 번의 결과. 출력은 stderr 를 합친 것이다 — 훅이 안내를 stderr 로 내보낸다. */
  record Execution(int exitCode, String output) {}
}
