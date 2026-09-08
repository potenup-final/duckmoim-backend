package com.duckmoim.safety.domain;

import java.time.LocalDateTime;

/**
 * 제재 중인 유저가 쓸 수 있는지 판정한다 (I-14).
 *
 * <p><b>도메인-모델링.md 「3.3 경계를 넘는 불변식」이 모양을 정해 두었다</b> — <i>"정책 객체(도메인 서비스). service 가 {@code
 * Sanction} 을 조회해 넘기고 판정만 맡긴다."</i> 그래서 이 클래스가 저장소를 부르지 않고 이미 읽어 온 제재를 받는다. 애그리게이트 하나로는 지킬 수 없는
 * 불변식이라 (Safety → Companion) 판정 자리를 하나로 모은 것이다.
 *
 * <p><b>제재가 없는 경우를 여기서 다룬다.</b> {@code Sanction} 엔티티는 「이 제재가 지금 막는가」까지만 답할 수 있고, 「제재가 아예 없다」는 그
 * 엔티티가 존재하지 않는 상태라 표현할 수 없다 — 그것이 이 정책이 따로 있는 이유다. 호출부가 null 을 두고 분기하면 그 분기가 경로마다 생긴다.
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
   * @param sanction 그 유저의 활성 제재. <b>없으면 null 이다</b> — 「제재가 없다」는 행이 없는 것으로 표현된다
   */
  public boolean canWrite(Sanction sanction, LocalDateTime now) {
    if (sanction == null) {
      return true;
    }

    return !sanction.blocksWritingAt(now);
  }
}
