package com.duckmoim.chat.domain;

/**
 * 사진의 EXIF 를 벗겼는가 (CH-16).
 *
 * <p><b>{@link ChatImageStatus} 와 다른 축이다.</b> 그쪽은 「어디까지 왔나」(발급 · 확정 · 첨부 · 정리)이고 이쪽은 「보여줘도 되나」다. 한
 * enum 에 섞으면 전송이 워커를 기다려야 한다 — 사용자는 확정 직후에 보내는데 워커는 최대 한 주기 뒤에 돈다.
 *
 * <pre>
 * 수명주기   PENDING → CONFIRMED → ATTACHED      전송은 이쪽만 본다
 * EXIF      PENDING ─────────────▶ STRIPPED     보여주는 쪽(CH-15)은 이쪽을 본다
 *                   └────────────▶ FAILED
 * </pre>
 */
public enum ExifStatus {

  /** 아직 안 벗겼다. 원본에 좌표가 있을 수 있다. */
  PENDING,

  /** 벗겼다. 저장소의 파일에 위치·식별 정보가 없다. */
  STRIPPED,

  /**
   * 벗기지 못했다. <b>영구히 보여주지 않는다.</b>
   *
   * <p>형식을 알 수 없거나 구조가 깨졌거나, 재시도를 다 썼다. 어느 쪽이든 원본에 무엇이 남았는지 모른다.
   */
  FAILED
}
