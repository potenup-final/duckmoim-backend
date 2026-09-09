package com.duckmoim.identity.domain;

import java.util.Optional;

/**
 * 프로필 이미지 저장소 (AU-08).
 *
 * <p><b>포트가 domain 에 있다.</b> 처음 {@code identity.service} 에 두었다가 <b>게이트가 잡아서 옮겼다</b> — 구현이 {@code
 * identity.infra} 에 있으므로 포트를 service 에 두면 {@code infra → service} 참조가 생기고, {@code LAYER_DEPENDENCY}
 * 가 <i>"infra 는 service 에서만 참조된다"</i> 로 그 방향을 막는다. 실측한 위반 일곱 건이 전부 이 한 가지 원인이었다.
 *
 * <p>C 티켓의 {@code SanctionReader} 는 service 에 있는데, <b>그 구현({@code NoneSanctionReader})도 service 에
 * 있어서</b> 같은 문제가 생기지 않았다. 자리를 그것과 맞추면 안 된다 — {@code TokenProvider}(domain) ← {@code
 * JwtProvider}(infra) 가 이 모양의 선례다.
 *
 * <p>인터페이스와 반환 타입에 프레임워크가 없어 {@code DOMAIN_IS_FRAMEWORK_FREE} 를 지킨다.
 *
 * <p><b>업로드 메서드가 없다.</b> 파일이 서버를 지나지 않기 때문이다 — 서버가 하는 일은 <b>서명</b>과 <b>확인</b> 둘뿐이다. 그래서 이 포트에
 * {@code InputStream} 도 {@code byte[]} 도 나오지 않는다.
 */
public interface ProfileImageStorage {

  /**
   * 그 키로 올릴 수 있는 서명된 주소를 만든다.
   *
   * <p><b>{@code contentType} 을 서명에 묶는다.</b> 클라이언트가 다른 형식을 올리면 서명이 어긋나 저장소가 거절한다 — 발급 때의 선언 검사와 확정
   * 때의 실제 검사 사이를 좁히는 층이다.
   */
  String presignUpload(String objectKey, String contentType);

  /** 그 키로 올라간 것이 있으면 메타데이터를, 없으면 빈 값을 준다. 확정 단계의 판정 근거다. */
  Optional<UploadedImage> findUploaded(String objectKey);

  /** 저장된 {@code profileImageUrl} 이 될 공개 주소. 앞부분은 설정에서 오므로 CloudFront 를 세우면 이 값만 바뀐다. */
  String publicUrlOf(String objectKey);
}
