package com.duckmoim.admin.domain;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 감사 로그 목록의 커서 (AD-05).
 *
 * <p>정렬 키 {@code (at, id)} 를 담는다. <b>최신순이다.</b>
 *
 * <p><b>최신순이라는 근거.</b> API-설계.md 「3. 커서 정의」의 표에 감사 로그 행이 없다. 같은 표의 「신고 (백오피스) = 최신순 {@code
 * (createdAt, id)}」를 따랐다 — 백오피스의 두 목록이 서로 다른 방향으로 흐르면 같은 화면에서 어긋난다. {@code MyCommentCursor} 가 표에 행이
 * 없을 때 같은 방식으로 정한 선례다.
 *
 * <p><b>다른 커서 타입을 돌려 쓰지 않는다</b> (STAR-59 에서 결정). 값의 모양이 같아 재사용하고 싶어지는 자리인데, 이어 읽는 부등호가 뒤집히면 판독은 되고
 * 페이지 경계만 조용히 어긋난다.
 *
 * <p>{@code id} 가 같이 들어가는 이유 — {@code at} 만으로는 같은 시각에 쌓인 기록 사이에 순서가 없어 페이지 경계에서 누락·중복이 생긴다. 감사 로그는
 * 한 번의 처리에서 여러 줄이 잇달아 쌓일 수 있어 같은 시각이 실제로 나온다.
 *
 * <p>{@code at} 은 저장된 값 그대로 UTC 다. 커서는 클라이언트에게 불투명하므로 KST 로 바꿔 담을 이유가 없고, 바꾸면 DB 비교값과 어긋난다.
 *
 * <p>Base64 는 암호가 아니라 <b>구조를 감춰 클라이언트가 값을 해석하고 의존하는 것을 막는</b> 장치다 (API 컨벤션).
 */
public record AuditLogCursor(LocalDateTime at, Long id) {

  private static final String DELIMITER = "|";

  public AuditLogCursor {
    if (at == null || id == null) {
      throw new IllegalArgumentException("커서는 at 과 id 를 모두 갖는다");
    }
  }

  /** 형식이 어긋나면 {@link IllegalArgumentException} 이다 — 상위가 INVALID_INPUT 400 으로 옮긴다. */
  public static AuditLogCursor decode(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      throw new IllegalArgumentException("커서가 비어 있다");
    }

    String plain;
    try {
      plain = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("커서를 판독할 수 없다", e);
    }

    // limit 을 -1 로 두어야 "2026-09-04T01:00|" 처럼 뒤가 빈 입력이 조용히 한 조각으로 줄지 않는다.
    String[] parts = plain.split("\\" + DELIMITER, -1);
    if (parts.length != 2) {
      throw new IllegalArgumentException("커서 형식이 어긋난다");
    }

    try {
      return new AuditLogCursor(LocalDateTime.parse(parts[0]), Long.parseLong(parts[1]));
    } catch (DateTimeParseException | NumberFormatException e) {
      throw new IllegalArgumentException("커서 값이 어긋난다", e);
    }
  }

  public String encode() {
    String plain = at + DELIMITER + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
  }
}
