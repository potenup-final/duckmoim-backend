package com.duckmoim.companion.infra;

import com.duckmoim.companion.domain.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * <b>조회 메서드를 두지 않는다.</b> 작성 경로가 저장소에 묻는 것은 「부모 댓글 한 건」뿐이고 그것은 findById 다.
 *
 * <p>목록 조회(CM-06 · CM-07)는 루트 기준 커서와 부모에 딸린 대댓글 묶음이 필요해 전용 메서드가 생긴다. 그 모양은 정렬 키를 쥔 그 티켓이 정한다 — 여기서
 * 미리 지어내면 쓰지 않는 메서드가 남고, 그 메서드가 다음 담당의 기준선이 된다.
 *
 * <p><b>커스텀 프래그먼트가 둘이다.</b> 정렬 방향과 조인 대상이 갈려 한 인터페이스에 담기지 않는다 — 목록은 작성 시간 오름차순으로 작성자를 붙이고, 내 내역은
 * 최신순으로 모집글을 붙인다 (CM-16). 나눠 두어도 service 에는 저장소 하나만 주입된다.
 */
public interface CommentRepository
    extends JpaRepository<Comment, Long>, CommentQueryRepository, MyCommentQueryRepository {}
