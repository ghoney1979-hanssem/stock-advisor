package com.stockadvisor.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 한국투자증권(KIS) REST API 연동 설정.
 * 국내 주식 시세(현재가) 조회에 사용하며, OAuth 접근토큰 발급에 app-key/app-secret 이 필요하다.
 *
 * <p>주문·잔고 API는 시세와 달리 계좌번호가 필수다(시세 조회는 불요).
 * {@code accountNumber}=종합계좌 8자리(CANO), {@code accountProductCode}=상품코드 2자리(ACNT_PRDT_CD, 종합위탁 보통 "01").
 * dry-run(주문 미전송)에선 비어 있어도 동작하고, LIVE 실주문 시에만 필요하다.</p>
 */
@ConfigurationProperties(prefix = "stockadvisor.kis")
public record KisProperties(
        String baseUrl,
        String appKey,
        String appSecret,
        String accountNumber,
        String accountProductCode
) {
}