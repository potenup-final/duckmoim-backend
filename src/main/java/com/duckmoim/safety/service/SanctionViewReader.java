package com.duckmoim.safety.service;

import com.duckmoim.identity.service.SanctionReader;
import com.duckmoim.identity.service.SanctionView;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code /users/me} 에 제재 상태를 실어 주는 실구현 (AU-12).
 *
 * <p><b>{@code identity} 가 내놓은 포트를 Safety 가 꽂는다.</b> {@code SanctionReader} 가 <i>"포트만 두고 실구현은
 * Safety 가 꽂는다. 포트가 없으면 AU-12 를 하는 쪽이 내 조회 서비스를 고쳐야 하고, 그 순간 {@code identity} 안에 {@code safety} 의존이
 * 생긴다"</i> 고 적어 두었다. 화살표는 그대로 Safety → Identity 다 (컨텍스트 맵).
 *
 * <p><b>{@code NoneSanctionReader} 를 지웠다.</b> 그 클래스가 <i>"Safety 담당이 실구현을 만들면 이 클래스를 지운다. 남겨 두면 빈이
 * 둘이 되어 주입이 실패하므로 잊고 지나갈 수 없다"</i> 고 적어 둔 그대로다.
 *
 * <p><b>{@code kind} 를 문자열로 넘긴다.</b> {@code SanctionView} 가 열거형을 두지 않은 것은 값 집합이 Safety 의 것이라
 * <i>"여기서 열거형을 만들면 같은 값 집합이 두 곳에 생긴다"</i> 는 이유였고, 이제 그 열거값 이름을 그대로 넣는다.
 */
@Component
@RequiredArgsConstructor
public class SanctionViewReader implements SanctionReader {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final SanctionQueryService sanctionQueryService;

  /**
   * 제재가 없으면 {@code NONE} 이다.
   *
   * <p>응답에서 {@code sanction} 키를 빼지 않는다 — {@code NoneSanctionReader} 가 <i>"그러면 클라이언트가 키가 생기는 날 코드를
   * 고쳐야 한다"</i> 고 정한 판단을 그대로 잇는다.
   */
  @Override
  public SanctionView read(Long userId) {
    return sanctionQueryService
        .findActive(userId)
        .map(SanctionViewReader::view)
        .orElseGet(SanctionView::none);
  }

  /** 시각은 저장이 UTC 이고 응답이 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」). */
  private static SanctionView view(ActiveSanction sanction) {
    return new SanctionView(
        sanction.kind().name(),
        sanction.reason(),
        toKst(sanction.until()),
        toKst(sanction.issuedAt()));
  }

  private static OffsetDateTime toKst(LocalDateTime storedInUtc) {
    if (storedInUtc == null) {
      return null;
    }

    return storedInUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toOffsetDateTime();
  }
}
