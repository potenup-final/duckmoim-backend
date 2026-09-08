package com.duckmoim.companion.domain;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 모집글 목록의 커서 (PO-08).
 *
 * <p>정렬 키 {@code (meetAt, id)} 를 그대로 담는다 (API-설계.md 「3. 커서 정의」). <b>커서 키는 정렬 키와 같아야 한다</b> — 어긋나면
 * 페이지 경계에서 누락·중복이 생기고, PO-08 의 검증 기준이 그것 하나다.
 *
 * <p>id 가 같이 들어가는 이유 — {@code meetAt} 은 중복이 생긴다. 같은 시각에 만나는 글이 페이지 경계에 걸리면 순서가 정해지지 않아 한 건이 두 번
 * 나오거나 아예 빠진다. {@code CommentCursor} 가 {@code (createdAt, id)} 로, {@code EventCursor} 가 {@code
 * (endsOn, id)} 로 같은 판단을 했다.
 *
 * <p><b>{@code status} 는 커서에 담지 않는다.</b> 커서는 정렬 키를 담는 것이고 {@code status} 는 거르는 조건이다. 필터가 바뀌면 클라이언트가
 * 첫 페이지부터 다시 부른다.
 *
 * <p>{@code meetAt} 은 저장된 값 그대로 UTC 다. 커서는 클라이언트에게 불투명하므로 KST 로 바꿔 담을 이유가 없고, 바꾸면 DB 비교값과 어긋난다.
 *
 * <p>Base64 는 암호가 아니라 <b>구조를 감춰 클라이언트가 값을 해석하고 의존하는 것을 막는</b> 장치다 (API 컨벤션).
 */
public record PostCursor(LocalDateTime meetAt, Long id) {

  private static final String DELIMITER = "|";

  public PostCursor {
    if (meetAt == null || id == null) {
      throw new IllegalArgumentException("커서는 meetAt 과 id 를 모두 갖는다");
    }
  }

  /** 형식이 어긋나면 {@link IllegalArgumentException} 이다 — 상위가 INVALID_INPUT 400 으로 옮긴다. */
  public static PostCursor decode(String encoded) {
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
      return new PostCursor(LocalDateTime.parse(parts[0]), Long.parseLong(parts[1]));
    } catch (DateTimeParseException | NumberFormatException e) {
      throw new IllegalArgumentException("커서 값이 어긋난다", e);
    }
  }

  public String encode() {
    String plain = meetAt + DELIMITER + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
  }
}
