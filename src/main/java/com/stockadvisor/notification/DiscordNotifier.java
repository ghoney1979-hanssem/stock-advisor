package com.stockadvisor.notification;

import com.stockadvisor.config.properties.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Discord Webhook 으로 알림을 전송한다.
 * webhook-url 이 비어 있으면 실제 발송 대신 로그만 남긴다(dry-run).
 */
@Component
public class DiscordNotifier {

    private static final Logger log = LoggerFactory.getLogger(DiscordNotifier.class);
    // Discord 메시지 본문 길이 제한
    private static final int MAX_CONTENT = 2000;

    private final RestClient restClient;
    private final NotificationProperties properties;

    public DiscordNotifier(RestClient.Builder builder, NotificationProperties properties) {
        this.properties = properties;
        this.restClient = builder.build();
    }

    /**
     * 메시지를 Discord 로 전송한다.
     *
     * @return 실제 발송 여부 (dry-run 이면 false)
     */
    public boolean send(String content) {
        String message = content.length() > MAX_CONTENT
                ? content.substring(0, MAX_CONTENT) : content;

        if (!properties.discord().isEnabled()) {
            log.info("[Discord dry-run] webhook 미설정 — 발송 생략. 내용:\n{}", message);
            return false;
        }
        try {
            restClient.post()
                    .uri(properties.discord().webhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("content", message))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Discord 알림 발송 완료");
            return true;
        } catch (RestClientException ex) {
            // 알림 실패가 신호 평가 파이프라인을 멈추지 않도록 예외를 삼키고 로그만 남긴다.
            log.error("Discord 알림 발송 실패", ex);
            return false;
        }
    }
}
