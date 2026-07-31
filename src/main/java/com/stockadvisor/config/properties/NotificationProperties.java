package com.stockadvisor.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 알림 채널 설정. 현재 Discord Webhook 을 지원한다.
 *
 * @param discord Discord 관련 설정
 */
@ConfigurationProperties(prefix = "stockadvisor.notification")
public record NotificationProperties(
        Discord discord
) {

    /**
     * @param webhookUrl Discord Incoming Webhook URL. 비어 있으면 dry-run(로그만).
     */
    public record Discord(String webhookUrl) {
        public boolean isEnabled() {
            return webhookUrl != null && !webhookUrl.isBlank();
        }
    }
}
