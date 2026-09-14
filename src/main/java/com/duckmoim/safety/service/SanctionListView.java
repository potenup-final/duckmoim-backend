package com.duckmoim.safety.service;

import com.duckmoim.safety.domain.SanctionKind;
import java.time.LocalDateTime;

/**
 * 백오피스 목록에 실리는 제재 한 건 (AD-10).
 *
 * <p>엔티티를 service 의 public 시그니처에 노출하지 않는다는 규칙(아키텍처-컨벤션.md 「service」)에 따라 둔 결과 객체다. 값 객체와 enum 은
 * presentation 이 참조해도 된다 (같은 문서 「의존성 방향」 3번).
 *
 * <p><b>{@code ActiveSanction} 을 돌려 쓰지 않는다.</b> 그쪽은 <b>본인에게</b> 자기 제재를 보여주는 값이라 누구인지가 없고 (AU-12),
 * 해제 경로에 넣을 {@code sanctionId} 도 없다. 여기에 두 필드를 더하면 본인 안내 응답에 관리자용 값이 섞인다.
 *
 * @param sanctionId 해제 경로에 그대로 넣는 값이라 {@code id} 가 아니다 (API-설계.md 「2-7. 백오피스 (Admin)」)
 * @param nickname 제재받은 회원의 닉네임. 카카오 회원번호는 싣지 않는다
 * @param reason 본인에게 보여주는 정보라 관리자에게도 그대로 보인다 (AD-04)
 * @param until 관리자가 입력한 값. {@code SUSPENDED} 일 때만 있다 (화면 계약 「제재 상태」)
 * @param expiresAt 서버가 계산한 해소 시각. {@code WARNED} 에도 있고 스스로 풀리지 않는 제재에는 없다
 */
public record SanctionListView(
    Long sanctionId,
    Long userId,
    String nickname,
    SanctionKind kind,
    String reason,
    LocalDateTime issuedAt,
    LocalDateTime until,
    LocalDateTime expiresAt) {}
