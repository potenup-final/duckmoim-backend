package com.duckmoim.companion.domain;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 유저가 쓴 모집글 목록의 커서 (AU-09 · AU-10).
 *
 * <p><b>{@link PostCursor} 를 돌려 쓰지 않는다.</b> 같은 {@code companion_post} 를 읽는데 정렬 키가 다르다 — 목록(PO-08)은
 * 만남시각 임박순 {@code (meetAt, id)} 이고 이쪽은 <b>작성 최신순</b> {@code (createdAt, id)} 다 (API-설계.md 「3. 커서
 * 정의」).
 *
 * <p>그 문서가 함정까지 적어 두었다 — <i>"커서 값의 모양이 같아 타입을 돌려 쓰고 싶어지는 자리인데, 이어 읽는 부등호가 뒤집혀서 <b>판독은 되고 페이지 경계만
 * 조용히 어긋난다.</b> 목록마다 커서 타입을 따로 둔다"</i>. 둘 다 {@code (시각, id)} 두 값이라 서로의 문자열이 <b>디코딩까지 성공한다</b> — 그래서
 * 잘못 쓰면 예외가 아니라 누락으로 나타난다.
 *
 * <p>모양은 {@link MyCommentCursor} 와 같다. 방향도 같은 내림차순이라 부등호가 같다.
 */
public record UserPostCursor(LocalDateTime createdAt, Long id) {

  private static final String DELIMITER = "|";

  public UserPostCursor {
    if (createdAt == null || id == null) {
      throw new IllegalArgumentException("커서는 createdAt 과 id 를 모두 갖는다");
    }
  }

  public static UserPostCursor decode(String encoded) {
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
      return new UserPostCursor(LocalDateTime.parse(parts[0]), Long.parseLong(parts[1]));
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
