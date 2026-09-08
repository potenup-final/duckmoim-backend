package com.duckmoim.companion.domain;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 댓글 목록의 커서 (CM-07).
 *
 * <p>정렬 키 {@code (createdAt, id)} 를 그대로 담는다 (API-설계.md 「3. 커서 정의」). <b>루트 댓글의 것만 담는다</b> — 그 문서가
 * 이유를 적어 두었다: <i>"대댓글까지 세면 페이지 경계에서 부모와 자식이 갈라진다."</i>
 *
 * <p>id 가 같이 들어가는 것이 CM-07 의 검증 기준 그 자체다. {@code createdAt} 만으로는 같은 시각에 달린 댓글들 사이에 순서가 없어 페이지 경계에서
 * 누락·중복이 생긴다. {@code EventCursor} 가 같은 이유로 {@code (endsOn, id)} 를 쓴다.
 *
 * <p>{@code createdAt} 은 저장된 값 그대로 UTC 다. 커서는 클라이언트에게 불투명하므로 KST 로 바꿔 담을 이유가 없고, 바꾸면 DB 비교값과 어긋난다.
 *
 * <p>Base64 는 암호가 아니라 <b>구조를 감춰 클라이언트가 값을 해석하고 의존하는 것을 막는</b> 장치다 (API 컨벤션).
 */
public record CommentCursor(LocalDateTime createdAt, Long id) {

  private static final String DELIMITER = "|";

  public CommentCursor {
    if (createdAt == null || id == null) {
      throw new IllegalArgumentException("커서는 createdAt 과 id 를 모두 갖는다");
    }
  }

  /** 형식이 어긋나면 {@link IllegalArgumentException} 이다 — 상위가 INVALID_INPUT 400 으로 옮긴다. */
  public static CommentCursor decode(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      throw new IllegalArgumentException("커서가 비어 있다");
    }

    String plain;
    try {
      plain = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("커서를 판독할 수 없다", e);
    }

    // limit 을 -1 로 두어야 "2026-09-14T09:00|" 처럼 뒤가 빈 입력이 조용히 한 조각으로 줄지 않는다.
    String[] parts = plain.split("\\" + DELIMITER, -1);
    if (parts.length != 2) {
      throw new IllegalArgumentException("커서 형식이 어긋난다");
    }

    try {
      return new CommentCursor(LocalDateTime.parse(parts[0]), Long.parseLong(parts[1]));
    } catch (DateTimeParseException | NumberFormatException e) {
      throw new IllegalArgumentException("커서 값이 어긋난다", e);
    }
  }

  public String encode() {
    String plain = createdAt + DELIMITER + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
  }
}
