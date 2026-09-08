package com.duckmoim.companion.domain;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 내 댓글 내역의 커서 (CM-16).
 *
 * <p>정렬 키 {@code (createdAt, id)} 를 담는다. <b>{@link CommentCursor} 와 값의 모양은 같지만 방향이 반대다</b> — 모집글의
 * 댓글 목록은 루트 작성 시간 <b>오름차순</b>이고 (API-설계.md 「3. 커서 정의」), 내 내역은 작성 <b>최신순</b>이다. 같은 타입을 돌려 쓰면 판독은 되지만
 * 페이지 경계가 뒤집혀 조용히 어긋난다.
 *
 * <p><b>최신순이라는 근거.</b> API-설계.md 「3. 커서 정의」의 표에 내 댓글 내역 행이 없다. 같은 표의 「유저가 쓴 모집글 = 작성 최신순」을 따랐고,
 * 화면-계약.md 의 내 내역 예시도 최신이 위다.
 *
 * <p>id 가 같이 들어가는 이유는 {@code CommentCursor} 와 같다. {@code createdAt} 만으로는 같은 시각에 달린 댓글 사이에 순서가 없어
 * 페이지 경계에서 누락·중복이 생긴다.
 *
 * <p>{@code createdAt} 은 저장된 값 그대로 UTC 다. 커서는 클라이언트에게 불투명하므로 KST 로 바꿔 담을 이유가 없고, 바꾸면 DB 비교값과 어긋난다.
 *
 * <p>Base64 는 암호가 아니라 <b>구조를 감춰 클라이언트가 값을 해석하고 의존하는 것을 막는</b> 장치다 (API 컨벤션).
 */
public record MyCommentCursor(LocalDateTime createdAt, Long id) {

  private static final String DELIMITER = "|";

  public MyCommentCursor {
    if (createdAt == null || id == null) {
      throw new IllegalArgumentException("커서는 createdAt 과 id 를 모두 갖는다");
    }
  }

  /** 형식이 어긋나면 {@link IllegalArgumentException} 이다 — 상위가 INVALID_INPUT 400 으로 옮긴다. */
  public static MyCommentCursor decode(String encoded) {
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
      return new MyCommentCursor(LocalDateTime.parse(parts[0]), Long.parseLong(parts[1]));
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
