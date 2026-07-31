package com.stockadvisor.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DART(금융감독원 전자공시시스템) OpenAPI 연동 설정.
 * opendart.fss.or.kr 에서 발급받은 인증키를 사용한다.
 */
@ConfigurationProperties(prefix = "stockadvisor.dart")
public record DartProperties(
        String baseUrl,
        String apiKey
) {
}