package com.duckmoim.companion.domain;

import com.duckmoim.common.domain.BaseEntity;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.exception.CommentErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 모집글에 달리는 댓글 (CM-01 · CM-02 · CM-03).
 *
 * <p><b>CompanionPost 밖의 애그리게이트다</b> (도메인-모델링.md 「3.1 경계와 트랜잭션 범위」). postId 로 ID 만 참조하고 객체 참조를 두지
 * 않는다 — 댓글 규칙 중 모집글을 바꿔야 하는 것이 하나도 없어서, 읽기만 하는 관계는 애그리게이트를 합칠 근거가 되지 않는다.
 *
 * <p>대댓글은 별도 타입이 아니라 parentId 가 있는 Comment 다 (도메인-모델링.md 「1. 유비쿼터스 언어」).
 *
 * <p><b>본문 500자 제한을 여기서 다시 보지 않는다.</b> API-컨벤션.md 「Validation 규칙」이 단순 형식 검증을 Bean Validation 으로
 * 정했고, comment.content 가 VARCHAR(500) 이라 그 둘을 지나지 않는 경로가 없다. 같은 숫자를 세 곳에 두면 한 곳만 고치는 날이 온다.
 */
@Entity
@Table(name = "comment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comment extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // 애그리게이트 밖은 ID 로만 참조한다 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」).
  @Column(name = "post_id", nullable = false)
  private Long postId;

  @Column(name = "author_id", nullable = false)
  private Long authorId;

  @Column(name = "parent_id")
  private Long parentId;

  @Column(name = "content", nullable = false, length = 500)
  private String content;

  // boolean 에 is 접두어를 붙이지 않는다 (API-컨벤션.md 「필드 표기 규칙」).
  @Column(name = "secret", nullable = false)
  private boolean secret;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private CommentStatus status;

  private Comment(Long postId, Long authorId, Long parentId, String content, boolean secret) {
    this.postId = postId;
    this.authorId = authorId;
    this.parentId = parentId;
    this.content = content;
    this.secret = secret;
    this.status = CommentStatus.ACTIVE;
  }

  /** 루트 댓글을 쓴다 (CM-01). 비밀 여부는 작성할 때만 정하고 수정으로 바꿀 수 없다 (CM-09). */
  public static Comment root(Long postId, Long authorId, String content, boolean secret) {
    return new Comment(postId, authorId, null, content, secret);
  }

  /**
   * 이 댓글에 대댓글을 쓴다 (CM-02).
   *
   * <p><b>깊이는 1을 넘지 않는다</b> (I-06). 도메인-모델링.md 「5. 불변식」이 검증 위치를 「생성 시」로 정했고 이중 방어가 「부모의 parentId
   * 확인」이라 DB 제약이 없다 — 여기가 유일한 방어선이다. 그래서 service 가 아니라 이 자리에 둔다.
   */
  public Comment reply(Long authorId, String content, boolean secret) {
    if (isReply()) {
      throw new BusinessException(CommentErrorCode.COMMENT_DEPTH_EXCEEDED);
    }

    return new Comment(postId, authorId, id, content, secret);
  }

  public boolean isReply() {
    return parentId != null;
  }

  /**
   * 자리표시자로만 보이는 댓글에는 답글을 달 수 없다.
   *
   * <p>API-설계.md 「2-5. 댓글 (Companion)」이 availableActions 를 두고 <i>"status 가 ACTIVE 가 아니면 배열이 빈다. 지운
   * 댓글에 답글이 달리면 안 된다"</i> 고 정했다. 화면이 버튼을 감추는 것과 별개로 서버가 막아야 한다.
   */
  public boolean isActive() {
    return status == CommentStatus.ACTIVE;
  }

  public boolean belongsTo(Long postId) {
    return this.postId.equals(postId);
  }
}
