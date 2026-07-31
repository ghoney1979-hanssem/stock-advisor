package com.stockadvisor.service;

import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.notification.DiscordNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 앱 기동 시 현재 실전 매매 태세(모드·화이트리스트·수동승인)를 Discord로 통지한다.
 * 무인 실매매에서 예기치 않은 재시작/재배포를 사람이 인지하기 위한 운영 알림.
 * (다운타임 자체 감지는 별도 하트비트가 필요 — 후속.)
 */
@Component
public class StartupNotifier {

    private static final Logger log = LoggerFactory.getLogger(StartupNotifier.class);

    private final DiscordNotifier discordNotifier;
    private final TradingPolicyProperties policy;

    public StartupNotifier(DiscordNotifier discordNotifier, TradingPolicyProperties policy) {
        this.discordNotifier = discordNotifier;
        this.policy = policy;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String whitelist = policy.liveStrategies() == null || policy.liveStrategies().isEmpty()
                ? "(비어있음)" : String.join(",", policy.liveStrategies());
        String msg = String.format("♻️ **앱 기동** — 모드 %s / enabled %s / 수동승인 %s / 화이트리스트 %s",
                policy.mode(), policy.enabled(), policy.manualConfirm(), whitelist);
        try {
            discordNotifier.send(msg);
        } catch (Exception e) {
            log.warn("기동 알림 발송 실패: {}", e.getMessage());
        }
    }
}
