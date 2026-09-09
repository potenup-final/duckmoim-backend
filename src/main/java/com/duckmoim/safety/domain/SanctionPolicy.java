package com.duckmoim.safety.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 제재 중인 유저가 쓸 수 있는지 판정한다 (I-14).
 *
 * <p><b>도메인-모델링.md 「3.3 경계를 넘는 불변식」이 모양을 정해 두었다</b> — <i>"정책 객체(도메인 서비스). service 가 {@code
 * Sanction} 을 조회해 넘기고 판정만 맡긴다."</i> 그래서 이 클래스가 저장소를 부르지 않고 이미 읽어 온 제재를 받는다. 애그리게이트 하나로는 지킬 수 없는
 * 불변식이라 (Safety → Companion) 판정 자리를 하나로 모은 것이다.
 *
 * <p><b>활성 제재를 전부 받는다.</b> 한 건만 받으면 여러 건이 활성일 때 그중 하나만 보고 지나간다 — 정지와 경고가 함께 걸린 유저에게서 경고가 먼저 잡히면
 * <b>정지 중인 사람이 글을 쓴다.</b> 저장소가 굳이 목록을 돌려주는 이유가 이것이고, 차단 판정만큼은 전량을 봐야 그 계약이 살아난다.
 *
 * <p><b>「제재가 없다」는 빈 목록이다.</b> 행이 없는 것으로 표현되므로 호출부에 null 분기가 생기지 않는다.
 *
 * <p><b>{@code WARNED} 는 쓰기를 막지 않는다.</b> 도메인 6장 제재 축 표의 「쓰기」 열이 그렇고, 같은 문서가 <i>"막을 것이면 정지를 준다"</i>
 * 고 적었다. 「제재 중이면 차단」으로 한 줄 짜면 그 행이 조용히 깨진다.
 *
 * <p>상태를 갖지 않는 도메인 서비스다 (아키텍처-컨벤션.md 「패키지 구조」).
 */
public final class SanctionPolicy {

  /**
   * 이 유저가 지금 새 글을 쓸 수 있는가.
   *
   * <p><b>하나라도 막으면 못 쓴다.</b> 가장 최근 것이 아니라 가장 강한 것이 이긴다 — 활성 제재가 여럿일 때 최근 것만 보면 나중에 걸린 경고가 앞선 정지를
   * 가린다.
   *
   * @param activeSanctions 그 유저의 활성 제재 전량. <b>없으면 빈 목록이다</b>
   */
  public boolean canWrite(List<Sanction> activeSanctions, LocalDateTime now) {
    return activeSanctions.stream().noneMatch(sanction -> sanction.blocksWritingAt(now));
  }
}
