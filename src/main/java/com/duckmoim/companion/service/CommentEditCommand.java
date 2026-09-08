package com.duckmoim.companion.service;

/**
 * 댓글 수정 요청을 유스케이스의 어휘로 옮긴 것 (CM-09).
 *
 * @param requesterId 인증에서 나온다. 요청 본문으로 받지 않는 것이 남의 댓글을 고치는 것을 막는 장치다
 * @param secret 안 보내면 null 이다. 저장값과 다르면 400 이고 같으면 통과다
 */
public record CommentEditCommand(
    Long commentId, Long requesterId, String content, Boolean secret) {}
