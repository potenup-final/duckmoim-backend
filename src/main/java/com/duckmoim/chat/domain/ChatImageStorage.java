package com.duckmoim.chat.domain;

import java.util.Optional;

/**
 * 채팅 이미지 저장소 (CH-14 · CH-17).
 *
 * <p><b>포트가 domain 에 있다.</b> 구현이 {@code chat.infra} 에 있으므로 포트를 service 에 두면 {@code infra → service}
 * 참조가 생기고 {@code LAYER_DEPENDENCY} 가 그 방향을 막는다 — {@code ProfileImageStorage} 가 실측 위반 일곱 건으로 같은 자리를
 * 찾은 뒤 옮겨 간 곳이다.
 *
 * <p><b>업로드 메서드가 없다.</b> 파일이 서버를 지나지 않는다 — 서버가 하는 일은 <b>서명</b> · <b>확인</b> · <b>삭제</b> 셋이다. 그래서 이
 * 포트에 {@code InputStream} 도 {@code byte[]} 도 나오지 않는다.
 *
 * <p><b>{@code ProfileImageStorage} 와 합치지 않았다</b> (명세서 3장 미결 #2 · 계획서 8.1). 메서드 집합이 실제로 다르다.
 *
 * <pre>
 * 프로필   presignUpload · findUploaded · publicUrlOf
 * 채팅     presignUpload · findUploaded · delete          (+ CH-15 의 서명된 GET)
 *                                        ▔▔▔▔▔▔
 *              지울 일이 있다 — CH-17 이 고아 객체를 치운다
 * </pre>
 *
 * <p><b>{@code publicUrlOf} 가 없는 것이 이 포트의 요점이다.</b> {@code CH-15} 가 「공개 주소를 쓰지 않는다」로 정했으므로 <b>만들 수
 * 있는 메서드를 두지 않는다</b> — 있으면 쓰게 되고, 쓰면 저장된 값 전부가 그 티켓의 마이그레이션 대상이 된다. 합쳤다면 이 메서드를 상속받게 됐다.
 *
 * <p>인터페이스와 반환 타입에 프레임워크가 없어 {@code DOMAIN_IS_FRAMEWORK_FREE} 를 지킨다.
 */
public interface ChatImageStorage {

  /**
   * 그 키로 올릴 수 있는 서명된 주소를 만든다.
   *
   * <p><b>{@code contentType} 을 서명에 묶는다.</b> 클라이언트가 다른 형식을 올리면 서명이 어긋나 저장소가 거절한다 — 발급 때의 선언 검사와 확정
   * 때의 실제 검사 사이를 좁히는 층이다.
   */
  String presignUpload(String objectKey, String contentType);

  /** 그 키로 올라간 것이 있으면 메타데이터를, 없으면 빈 값을 준다. 확정 단계의 판정 근거다. */
  Optional<UploadedChatImage> findUploaded(String objectKey);

  /**
   * 그 키의 객체를 지운다 (CH-17).
   *
   * <p><b>없는 키를 지워도 조용하다.</b> 배치가 중복으로 돌 수 있고 (인스턴스가 둘이고 잠그지 않는다 · ADR 0009) 그때 둘째 호출은 이미 없는 것을 지운다
   * — 멱등이어야 배치를 잠그지 않아도 된다.
   *
   * <p><b>던지지 않는다.</b> 한 건의 삭제 실패가 그 주기의 나머지를 멈추면, 고아 하나 때문에 정리가 영구히 막힌다. 실패는 로그로 남기고 행을 남겨 다음 주기가
   * 다시 집는다.
   *
   * @return 지웠거나 이미 없으면 {@code true}. 실패했으면 {@code false} — 부르는 쪽이 행을 남긴다
   */
  boolean delete(String objectKey);
}
