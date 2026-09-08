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

  /**
   * 본문을 고친다 (CM-09).
   *
   * <p><b>비밀 여부는 바꿀 수 없다.</b> 화면-계약.md 가 <i>"secret 은 작성할 때만 정하고 수정으로 못 바꾼다"</i> 고 정했다. 공개였던 것을 비밀로
   * 돌려도 이미 읽은 사람을 되돌릴 수 없고, 반대로 비밀을 공개로 열면 권한자에게만 보이던 연락처가 전체에 열린다.
   *
   * <p>수정은 <b>작성자 본인만</b> 한다. 방장은 삭제만 할 수 있다 (API-설계.md 「2-5. 댓글 (Companion)」) — 남의 말을 고치는 것과 지우는
   * 것은 다른 권한이다.
   *
   * <p>본문 길이를 여기서 보지 않는다. 작성과 같은 이유다 — Bean Validation 과 VARCHAR(500) 을 지나지 않는 경로가 없다.
   *
   * @param secret 안 보내면 null 이고 그때는 판정하지 않는다. 저장값과 같은 값을 보내는 것도 통과다 — 프론트가 폼 전체를 되보내는 것을 막지 않으면서
   *     「비밀 변경 400」 이 성립한다
   */
  public void edit(Long requesterId, String content, Boolean secret) {
    requireActive();

    if (!authorId.equals(requesterId)) {
      throw new BusinessException(CommentErrorCode.COMMENT_NOT_AUTHOR);
    }
    if (secret != null && secret != this.secret) {
      throw new BusinessException(CommentErrorCode.COMMENT_SECRET_NOT_CHANGEABLE);
    }

    this.content = content;
  }

  /**
   * 소프트 삭제한다 (CM-10).
   *
   * <p><b>작성자 또는 방장이다.</b> 방장이 지울 수 있는 이유는 자기 모집글에 달린 글을 관리해야 하기 때문이고, 그래서 이 판정에 남의 애그리게이트의
   * 값({@code hostId})이 들어온다 — service 가 모집글을 읽어 넘긴다.
   *
   * <p>본문을 지우지 않는다. 도메인-모델링.md 「4. 엔티티 · 값 객체 · 식별자」가 Comment 를 소프트 삭제 대상으로 정했다. 조회에서 사라지는 것은
   * {@code CommentVisibilityPolicy} 가 {@code status != ACTIVE} 를 막기 때문이고, 그것으로 CM-10 의 「삭제 후 본문
   * 미노출」이 성립한다.
   *
   * <p>지운 뒤에도 하위 대댓글이 있으면 목록에 자리표시자로 남는다 (CM-11). 그 판정은 조회 쪽에 있다.
   */
  public void deleteBy(Long requesterId, Long hostId) {
    requireActive();

    boolean isAuthor = authorId.equals(requesterId);
    boolean isHost = hostId != null && hostId.equals(requesterId);

    if (!isAuthor && !isHost) {
      throw new BusinessException(CommentErrorCode.COMMENT_NOT_AUTHOR_OR_HOST);
    }

    this.status = CommentStatus.DELETED;
  }

  /**
   * 신고 처리 결과로 댓글을 가린다 (AD-07).
   *
   * <p><b>요청자를 받지 않는다.</b> 이 전이의 유일한 조건은 관리자라는 것이고, 그 판정은 관문 한 곳에 있다 (API-설계.md 「2-7. 백오피스
   * (Admin)」). {@code deleteBy} 가 요청자를 받는 것은 작성자·방장이라는 판정이 도메인 규칙이라서인데, 여기에는 그런 규칙이 없다 — 인가를 도메인으로
   * 끌어오면 관문과 두 곳에서 판정하게 된다.
   *
   * <p><b>본문을 지우지 않는다.</b> 조회에서 사라지는 것은 {@code CommentVisibilityPolicy} 가 {@code status != ACTIVE}
   * 를 막기 때문이다. 본문이 남아야 관리자가 CM-17 로 판단 재료를 볼 수 있고, 가려진 뒤에도 신고를 계속 받는다 (STAR-60).
   *
   * <p>하위 대댓글이 있으면 목록에 자리표시자로 남는다 (CM-11). 그 판정은 조회 쪽에 있고 {@code DELETED} 와 같은 분기를 탄다.
   *
   * <p><b>409 인 이유</b> — {@code requireActive} 의 404 를 쓰지 않는다. 도메인-모델링.md 「6. 라이프사이클」에서 {@code
   * DELETED} 와 {@code BLINDED} 는 각각 종착이고 둘 사이 전이가 없으므로, 여기 걸리는 것은 <b>종착 전이를 다시 부른 것</b>이다. 부르는 쪽이
   * 관리자라 그 댓글의 본문까지 읽을 수 있어 「없다」로 답하면 사실과 다르다.
   */
  public void blind() {
    if (status != CommentStatus.ACTIVE) {
      throw new BusinessException(CommentErrorCode.COMMENT_NOT_ACTIVE);
    }

    this.status = CommentStatus.BLINDED;
  }

  /**
   * 살아 있는 댓글만 고치거나 지울 수 있다.
   *
   * <p><b>404 인 이유</b> — API-컨벤션.md 「Status Code 규칙」이 <i>"소프트 삭제된 리소스는 404로 취급한다"</i> 고 정했다.
   * STAR-54 가 부모 댓글에 대해 이미 같은 판단을 했다. {@code BLINDED} 도 같게 본다 — 자리표시자는 존재만 남은 것이지 조작 대상이 아니다.
   *
   * <p>도메인-모델링.md 「6. 라이프사이클」에서 DELETED · BLINDED 가 종착이고 되돌아오는 전이가 없다.
   */
  private void requireActive() {
    if (status != CommentStatus.ACTIVE) {
      throw new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND);
    }
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
