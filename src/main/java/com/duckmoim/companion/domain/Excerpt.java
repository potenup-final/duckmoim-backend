package com.duckmoim.companion.domain;

/**
 * 목록 카드에 싣는 본문 미리보기 (PO-08).
 *
 * <p><b>서버가 잘라서 내린다.</b> API-설계.md 「2-4. 모집글 (Companion)」이 이유를 적어 두었다 — <i>"카드에 두 줄만 보이는데 20건치 본문을
 * 통째로 내리면 응답이 커진다."</i> 상세에서는 {@code content} 로 바뀐다 (PO-11).
 *
 * <p><b>길이가 어느 문서에도 없어서 정했다.</b> 화면 계약의 예시가 본문 500자 중 앞부분이고 개행이 공백으로 접혀 있다는 것만 근거로 남아 있다. 카드 두 줄에
 * 100자면 충분하고, 모자라도 화면이 잘라 그리므로 넘치는 것보다 낫다. <b>계약이 정해지면 이 상수 하나만 고친다.</b>
 *
 * <p><b>말줄임표를 붙이지 않는다.</b> 잘렸다는 표시는 화면의 일이다 — 카드 폭에 따라 두 줄이 끝나는 자리가 달라서 서버가 붙인 「…」 은 문장 중간에 뜬다.
 */
public final class Excerpt {

  private static final int MAX_LENGTH = 100;

  private Excerpt() {}

  /**
   * 본문을 미리보기로 줄인다.
   *
   * <p><b>개행을 공백으로 접는다.</b> 카드는 한 덩이의 문장으로 그려지는데 본문의 단락 구분이 그대로 실리면 두 줄 안에서 줄바꿈만 보인다. 화면 계약의 예시가 이미
   * 그 모양이다 — 상세의 {@code "...찾아요.\n\n오전 9시쯤..."} 이 목록에서 한 줄로 이어져 있다.
   *
   * @param content 본문. <b>선택 입력이라 {@code null} 일 수 있고 (PO-01), 그때는 {@code null} 을 돌려준다</b> — 빈 문자열로
   *     바꾸면 「본문 없는 글」과 「본문이 빈 글」이 화면에서 구분되지 않는다
   */
  public static String of(String content) {
    if (content == null) {
      return null;
    }

    String folded = content.replaceAll("\\s+", " ").strip();

    return folded.length() <= MAX_LENGTH ? folded : folded.substring(0, MAX_LENGTH);
  }
}
