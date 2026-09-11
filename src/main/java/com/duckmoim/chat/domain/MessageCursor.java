package com.duckmoim.chat.domain;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 메시지 목록의 커서 (CH-09).
 *
 * <p>정렬 키 {@code (id)} 하나를 담고 <b>최신순</b>이다.
 *
 * <p><b>표의 관행과 다르다.</b> API-설계.md 「3. 커서 정의」의 커서 일곱이 전부 {@code (정렬키, id)} 두 값인데 여기는 하나다. 그쪽이 {@code
 * id} 를 덧댄 것은 정렬키만으로 <b>동점이 생기기 때문</b>이고 ({@code NotificationCursor} 의 주석: <i>"워커가 한 주기에 여러 건을 보낼 때
 * 같은 마이크로초가 실제로 나온다"</i>), 메시지는 {@code id} 자체가 삽입 순서의 전순서라 덧댈 것이 없다. {@code createdAt} 을 함께 담으면 같은
 * 순서를 두 번 적고 동점자만 늘어난다.
 *
 * <p><b>이 결정이 뒤의 두 티켓을 싸게 만든다.</b> {@code V700} 이 「멤버별 마지막 읽은 지점」 컬럼을 미루면서 <i>"타입이 CH-09 의 커서 정의에
 * 달려 있다"</i> 고 적어 둔 자리다.
 *
 * <pre>
 * (createdAt, id) 였다면   CH-13 의 읽은 지점 = DATETIME + BIGINT 두 칸
 *                          CH-11 의 재연결 지점 = 두 값을 합친 무언가
 * id 하나라서             CH-13 = BIGINT 한 칸
 *                          CH-11 = SSE 의 id: 줄이 그대로 messageId
 * </pre>
 *
 * <p><b>그래도 Base64 로 감싼다.</b> API 컨벤션이 커서를 불투명 문자열로 정했고, 감싸 두면 <b>나중에 {@code (createdAt, id)} 로 바꿔도
 * 클라이언트가 안 깨진다.</b> 숫자를 그대로 내보내면 그 숫자에 의존하는 클라이언트가 생기고 그때는 못 바꾼다.
 *
 * <p><b>모양이 같은 커서가 생겨도 타입을 돌려 쓰지 않는다.</b> 값의 구성이 같아도 이어 읽는 부등호가 정렬 방향마다 다르다 — 돌려 쓰면 판독은 되고 페이지 경계만
 * 조용히 어긋난다. STAR-59 에서 「목록마다 커서 타입을 따로 둔다」로 정해진 자리다.
 */
public record MessageCursor(Long id) {

  public MessageCursor {
    if (id == null) {
      throw new IllegalArgumentException("커서는 id 를 갖는다");
    }
  }

  /** 형식이 어긋나면 {@link IllegalArgumentException} 이다 — 상위가 INVALID_INPUT 400 으로 옮긴다. */
  public static MessageCursor decode(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      throw new IllegalArgumentException("커서가 비어 있다");
    }

    String plain;
    try {
      plain = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("커서를 판독할 수 없다", e);
    }

    try {
      return new MessageCursor(Long.parseLong(plain));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("커서 값이 어긋난다", e);
    }
  }

  public String encode() {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(String.valueOf(id).getBytes(StandardCharsets.UTF_8));
  }
}
