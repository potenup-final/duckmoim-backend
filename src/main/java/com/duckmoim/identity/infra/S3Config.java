package com.duckmoim.identity.infra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 클라이언트와 서명자 (AU-08).
 *
 * <p><b>버킷 이름이 비어 있으면 빈을 만들지 않는다.</b> 자격증명이 없어도 <b>빈 생성 자체는 된다</b> — SDK 기본 공급자는 첫 호출에서 자격증명을 찾으므로
 * 기동은 성공하고 업로드만 {@code SdkClientException} 으로 죽는다. 그래서 조건이 필요하다. 같은 조건을 {@code
 * S3ProfileImageStorage} 에도 걸어 둘이 함께 뜨고 함께 물러난다.
 *
 * <p><b>{@code @ConditionalOnProperty} 가 아니라 표현식인 이유</b> — 그 조건은 <b>빈 문자열도 「값이 있다」로 본다.</b> 기본값이
 * {@code ${S3_BUCKET:}} 이라 키는 늘 존재하고, 그래서 로컬에서도 이 빈이 떠서 대역({@code StubProfileImageStorage})이 한 번도
 * 뜨지 않았다 — PR #89 리뷰에서 잡혔다. 표현식이 빈 문자열을 걸러 낸다.
 *
 * <p><b>자격증명을 명시하지 않는다.</b> SDK 기본 공급자가 순서대로 찾는다 — 환경변수 · 프로파일 · <b>EC2 인스턴스 역할</b>. 운영에서는 마지막 것이
 * 잡히고, 그래서 액세스 키를 서버에 두지 않는다.
 */
@Configuration
@ConditionalOnExpression("'${duckmoim.s3.bucket:}' != ''")
public class S3Config {

  @Bean
  public S3Client s3Client(@Value("${duckmoim.s3.region}") String region) {
    return S3Client.builder().region(Region.of(region)).build();
  }

  @Bean
  public S3Presigner s3Presigner(@Value("${duckmoim.s3.region}") String region) {
    return S3Presigner.builder().region(Region.of(region)).build();
  }
}
