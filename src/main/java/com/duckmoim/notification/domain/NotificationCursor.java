package com.duckmoim.notification.domain;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 알림함의 커서 (NT-08).
 *
 * <p>정렬 키 {@code (createdAt, id)} 를 담고 <b>최신순</b>이다 (API-설계.md 「3. 커서 정의」).
 *
 * <p><b>모양이 같은 커서가 이미 넷이지만 타입을 돌려 쓰지 않는다.</b> 값의 구성은 같아도 이어 읽는 부등호가 정렬 방향마다 다르다 — 돌려 쓰면 판독은 되고 페이지
 * 경계만 조용히 어긋난다. STAR-59 에서 「목록마다 커서 타입을 따로 둔다」로 정해진 자리다.
 *
 * <p>{@code id} 가 같이 들어가는 것은 성능이 아니라 정확성이다. {@code createdAt} 만으로는 <b>워커가 한 주기에 여러 건을 보낼 때</b> 같은
 * 마이크로초가 실제로 나오고, 그때 순서가 없으면 페이지 경계에서 누락·중복이 생긴다.
 *
 * <p>{@code createdAt} 은 저장된 값 그대로 UTC 다. 커서는 클라이언트에게 불투명하므로 KST 로 바꿔 담을 이유가 없고, 바꾸면 DB 비교값과 어긋난다.
 *
 * <p>Base64 는 암호가 아니라 <b>구조를 감춰 클라이언트가 값을 해석하고 의존하는 것을 막는</b> 장치다 (API 컨벤션).
 */
public record NotificationCursor(LocalDateTime createdAt, Long id) {

  private static final String DELIMITER = "|";

  public NotificationCursor {
    if (createdAt == null || id == null) {
      throw new IllegalArgumentException("커서는 createdAt 과 id 를 모두 갖는다");
    }
  }

  /** 형식이 어긋나면 {@link IllegalArgumentException} 이다 — 상위가 INVALID_INPUT 400 으로 옮긴다. */
  public static NotificationCursor decode(String encoded) {
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
      return new NotificationCursor(LocalDateTime.parse(parts[0]), Long.parseLong(parts[1]));
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
