package com.duckmoim.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 코드와 하네스가 인용하는 위키 경로·절 제목이 <b>실재하는지</b> 검사한다.
 *
 * <p>왜 필요한가 — 위키가 화면 계약을 한 파일로 합쳤을 때 {@code 화면-계약/행사.md} 를 가리키던 인용 여덟 곳이 통째로 죽었는데, 컴파일도 테스트도 CI 도
 * 전부 초록불이었다. 주석은 아무도 실행하지 않으므로 썩어도 신호가 없다. <b>그런데 에이전트는 그 주석을 컨텍스트로 읽는다.</b> 위키를 최신으로 맞춰도 코드 안 주석이
 * 옛 위키를 다시 주입하는 경로였고, 자동 동기화가 못 막는 유일한 구멍이었다.
 *
 * <p>두 가지를 본다.
 *
 * <ul>
 *   <li>{@code docs/wiki/...md} 전체 경로가 실제 파일인가
 *   <li>{@code 화면-계약.md 「정렬」} 처럼 파일명과 절 제목을 함께 적은 인용에서, 그 파일이 위키에 하나로 특정되고 그런 제목의 헤딩이 있는가
 * </ul>
 *
 * <p><b>장 번호는 검사하지 않는다.</b> 번호는 문서가 개정될 때마다 밀리므로 인용은 절 제목으로 건다. 이번에 죽은 인용들이 전부 장 번호였다.
 *
 * <p>위키는 서브모듈이고 CI 는 그것을 가져오지 않는다 ({@code actions/checkout} 의 기본값). 그래서 위키가 없으면 건너뛰되 <b>건너뛴 사실을
 * 남긴다</b> — 검사하지 않은 것과 통과한 것이 같아 보이면 안 된다.
 */
class WikiReferenceTest {

  private static final Path WIKI = Path.of("docs/wiki");

  /** 인용을 스캔할 곳. 코드뿐 아니라 하네스도 본다 — 같은 종류로 썩는다. */
  private static final List<Path> SCAN_ROOTS =
      List.of(Path.of("src"), Path.of(".claude"), Path.of("docs/harness"), Path.of("CLAUDE.md"));

  private static final Pattern FULL_PATH = Pattern.compile("docs/wiki/([^\\s`)\"'\\]]+?\\.md)");

  /** 파일명 + 「절 제목」. 사이에 "1장" 같은 것이 끼어도 잡는다. */
  private static final Pattern NAME_AND_SECTION =
      Pattern.compile("([가-힣A-Za-z0-9./_-]+\\.md)(?:\\s*\\d+장)?\\s*「([^」]{2,})」");

  @DisplayName("코드와 하네스가 인용하는 위키 경로가 모두 실재한다.")
  @Test
  void wikiPathsExist() {
    skipIfWikiMissing();

    List<String> dead = new ArrayList<>();
    for (Path source : sourcesToScan()) {
      Matcher matcher = FULL_PATH.matcher(normalize(read(source)));
      while (matcher.find()) {
        if (!Files.isRegularFile(WIKI.resolve(matcher.group(1)))) {
          dead.add(source + " → docs/wiki/" + matcher.group(1));
        }
      }
    }

    assertThat(dead).as("위키에 없는 경로를 인용하고 있다. 위키가 옮기거나 이름을 바꾼 문서다").isEmpty();
  }

  @DisplayName("코드와 하네스가 인용하는 위키 절 제목이 모두 실재한다.")
  @Test
  void wikiSectionTitlesExist() {
    skipIfWikiMissing();

    List<String> dead = new ArrayList<>();
    for (Path source : sourcesToScan()) {
      Matcher matcher = NAME_AND_SECTION.matcher(normalize(read(source)));
      while (matcher.find()) {
        String fileName = matcher.group(1);
        String section = matcher.group(2).trim();

        Path document = resolve(fileName);
        if (document == null) {
          dead.add(source + " → " + fileName + " (그런 문서가 없다)");
          continue;
        }
        if (headings(document).stream().noneMatch(h -> h.equals(section) || h.contains(section))) {
          dead.add(source + " → " + fileName + " 「" + section + "」");
        }
      }
    }

    assertThat(dead).as("그런 제목의 절이 위키에 없다. 문서가 절을 고쳤거나 인용이 틀렸다").isEmpty();
  }

  private static void skipIfWikiMissing() {
    if (!Files.isRegularFile(WIKI.resolve("README.md"))) {
      abort(
          "위키 서브모듈이 없어 인용 검사를 건너뛴다. CI 는 서브모듈을 가져오지 않으므로 정상이다."
              + " 로컬에서 돌리려면 git submodule update --init --remote docs/wiki");
    }
  }

  private static List<Path> sourcesToScan() {
    List<Path> files = new ArrayList<>();
    for (Path root : SCAN_ROOTS) {
      if (!Files.exists(root)) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(root)) {
        walk.filter(Files::isRegularFile)
            .filter(WikiReferenceTest::isScannable)
            .filter(WikiReferenceTest::isNotThisScanner)
            .forEach(files::add);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return files;
  }

  /**
   * 스캐너 자신은 뺀다. 여기 javadoc 이 인용 형태를 예시로 적고 있어서, 안 빼면 자기 예시를 죽은 인용으로 신고한다.
   *
   * <p>정규식으로 예시를 피해 쓰는 방법도 있지만 그러면 <b>설명을 못 적는다.</b> 규칙이 무엇을 잡는지 적을 수 없게 만드는 검사는 그 자체로 나쁘다.
   */
  private static boolean isNotThisScanner(Path path) {
    return !path.getFileName().toString().equals("WikiReferenceTest.java");
  }

  private static boolean isScannable(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".java") || name.endsWith(".md") || name.endsWith(".json");
  }

  /**
   * 인용한 이름을 실제 파일로 푼다. 못 풀면 {@code null} 이고, 그것이 곧 죽은 인용이다.
   *
   * <p>세 갈래로 찾는다 — 경로로 적었으면 위키 기준과 저장소 기준 둘 다, 파일명만 적었으면 위키에서 이름으로. 위키에 같은 이름이 여럿이면 특정할 수 없으므로 못 푼
   * 것으로 본다 (경로로 적어야 한다).
   *
   * <p>위키에 없으면 저장소 문서로 한 번 더 찾는다. {@code CLAUDE.md} 처럼 위키가 아닌 문서를 절 제목과 함께 인용하는 자리가 실재하고, 그것까지 죽었다고
   * 하면 안 된다.
   */
  private static Path resolve(String name) {
    String bare = name.startsWith("docs/wiki/") ? name.substring("docs/wiki/".length()) : name;

    for (Path candidate : List.of(WIKI.resolve(bare), Path.of(name))) {
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    if (name.contains("/")) {
      return null;
    }

    List<Path> byName = filesNamed(WIKI, name);
    if (byName.size() == 1) {
      return byName.get(0);
    }
    if (byName.size() > 1) {
      return null;
    }
    List<Path> local = filesNamed(Path.of("."), name);
    return local.size() == 1 ? local.get(0) : null;
  }

  private static List<Path> filesNamed(Path root, String fileName) {
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> !p.toString().contains("/build/") && !p.toString().contains("/.git/"))
          .filter(p -> p.getFileName().toString().equals(fileName))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> headings(Path markdown) {
    return read(markdown)
        .lines()
        .filter(line -> line.startsWith("#"))
        .map(line -> line.replaceFirst("^#{1,6}\\s*", "").replace("**", "").trim())
        .toList();
  }

  /**
   * javadoc 은 줄바꿈으로 인용이 끊긴다. 앞의 별표를 걷어내고 한 줄로 붙여 그 경계를 없앤다.
   *
   * <p>{@code @code} 태그도 백틱으로 편다. javadoc 관례는 태그고 마크다운 헤딩은 백틱이라, 펴주지 않으면 같은 제목을 가리키는 인용이 안 맞는 것으로
   * 잡힌다. 실제로 이 테스트를 처음 돌렸을 때 그 이유로 두 건이 걸렸다.
   */
  private static String normalize(String text) {
    return text.replaceAll("\\{@code\\s+([^}]+)}", "`$1`")
        .replaceAll("(?m)^\\s*\\*\\s?", " ")
        .replaceAll("\\s*\\R\\s*", " ");
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
