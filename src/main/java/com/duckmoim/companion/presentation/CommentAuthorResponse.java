package com.duckmoim.companion.presentation;

/**
 * 댓글 작성자 블록.
 *
 * <p><b>API-설계.md 「2-5. 댓글 (Companion)」은 이 블록을 넷으로 정했다</b> — {@code id} · {@code nickname} · {@code
 * profileImageUrl} · {@code lastSeen}. 여기 셋만 있는 것은 미완성이고, 그 이유는 이렇다.
 *
 * <p>{@code lastSeen} 은 마지막 접속 시각을 다섯 구간으로 바꾼 값이다 (도메인-모델링.md 「7.2 최근 접속일 노출」). 그 변환 코드가 아직 어디에도
 * 없고, 같은 값이 공개 프로필(AU-09)과 {@code /users/me} 에도 나간다. 7.2 가 <i>"본인 조회에서도 동일하게 구간으로 내린다. 경로마다 형태가 다르면
 * 조립 지점이 갈라진다"</i> 고 정했으므로 <b>회원 쪽에서 한 번만 만드는 것이 맞다.</b>
 *
 * <p>이 티켓(CM-04 · CM-05 · CM-08 · CM-20)의 완료 조건은 자리표시자에 <i>"아바타 · 닉네임 · 작성시각"</i> 이 남는 것까지이고, <b>노출
 * 엔드포인트가 없어 클라이언트에 약속된 것이 아직 없다.</b> 그래서 셋으로 두고 {@code lastSeen} 은 목록 조회(CM-06 · CM-07)로 넘긴다.
 *
 * <p>자리표시자에서도 이 블록은 그대로 내려간다 (CM-08). 가려지는 것은 본문뿐이다.
 */
public record CommentAuthorResponse(Long id, String nickname, String profileImageUrl) {}
