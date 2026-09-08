package com.duckmoim.companion.service;

import com.duckmoim.companion.domain.Comment;
import com.duckmoim.identity.domain.LastSeen;
import java.util.List;

/**
 * 목록에 실리는 댓글 한 건과 그 작성자.
 *
 * <p>엔티티를 그대로 올리지 않는 것과 같은 이유로 저장소의 조인 결과({@code AuthoredComment})도 그대로 올리지 않는다 — {@code
 * presentation} 은 {@code infra} 를 참조하지 못한다 (게이트 {@code LAYER_DEPENDENCY}).
 *
 * <p>{@code Comment} 자체는 domain 이라 presentation 도 볼 수 있다. 본문을 보여줄지 판정하려면 그 엔티티가 필요하다.
 *
 * @param lastSeen 구간 값. 원본 시각은 여기까지도 오지 않는다 (도메인-모델링.md 「7.2 최근 접속일 노출」)
 * @param replies 이 댓글의 대댓글. 루트가 아니면 항상 비어 있다 — 깊이가 1단계로 고정이다 (I-06)
 */
public record CommentView(
    Comment comment,
    String nickname,
    String profileImageUrl,
    LastSeen lastSeen,
    List<CommentView> replies) {}
