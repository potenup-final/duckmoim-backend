package com.duckmoim.identity.infra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 클라이언트와 서명자 (AU-08).
 *
 * <p><b>버킷이 설정되지 않으면 빈을 만들지 않는다.</b> 자격증명이 없는 환경에서 이 빈을 만들면 기동이 실패한다 — 로컬과 테스트가 그렇다. 같은 조건을 {@code
 * S3ProfileImageStorage} 에도 걸어 둘이 함께 뜨고 함께 물러난다.
 *
 * <p><b>자격증명을 명시하지 않는다.</b> SDK 기본 공급자가 순서대로 찾는다 — 환경변수 · 프로파일 · <b>EC2 인스턴스 역할</b>. 운영에서는 마지막 것이
 * 잡히고, 그래서 액세스 키를 서버에 두지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = "duckmoim.s3.bucket")
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
