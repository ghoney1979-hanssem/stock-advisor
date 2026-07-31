package com.stockadvisor.market;

import com.stockadvisor.common.ExternalApiException;
import com.stockadvisor.config.properties.KisProperties;
import com.stockadvisor.market.dto.KisTokenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 한국투자증권 OAuth 접근토큰 관리자.
 * KIS 접근토큰은 발급 후 약 24시간 유효하므로 메모리에 보관하고 만료 전 재발급한다.
 */
@Component
public class KisTokenManager {

    private static final Logger log = LoggerFactory.getLogger(KisTokenManager.class);

    private final RestClient restClient;
    private final KisProperties properties;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile String cachedToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    public KisTokenManager(RestClient.Builder builder, KisProperties properties) {
        this.properties = properties;
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
    }

    /** 유효한 접근토큰을 반환한다. 만료 60초 전이면 재발급한다. */
    public String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(expiresAt.minusSeconds(60))) {
            return cachedToken;
        }
        lock.lock();
        try {
            if (cachedToken != null && Instant.now().isBefore(expiresAt.minusSeconds(60))) {
                return cachedToken;
            }
            return issueNewToken();
        } finally {
            lock.unlock();
        }
    }

    private String issueNewToken() {
        try {
            KisTokenResponse response = restClient.post()
                    .uri("/oauth2/tokenP")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "grant_type", "client_credentials",
                            "appkey", properties.appKey(),
                            "appsecret", properties.appSecret()))
                    .retrieve()
                    .body(KisTokenResponse.class);

            if (response == null || response.accessToken() == null) {
                throw new ExternalApiException("KIS", "접근토큰 발급 응답이 비어 있습니다.");
            }

            cachedToken = response.accessToken();
            expiresAt = Instant.now().plusSeconds(response.expiresInSeconds());
            log.info("KIS 접근토큰 재발급 완료. 만료시각={}", expiresAt);
            return cachedToken;

        } catch (ExternalApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ExternalApiException("KIS", "접근토큰 발급 중 오류가 발생했습니다.", ex);
        }
    }
}
