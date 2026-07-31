package com.stockadvisor.service;

import com.stockadvisor.common.Disclaimer;
import com.stockadvisor.config.properties.TradingPolicyProperties;
import com.stockadvisor.notification.DiscordNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 장 시작 알림 — 시초의 <b>시장 국면</b>과 <b>전략 진입 태세</b>(정적 화이트리스트 + 게이트 통과 현황)를 Discord로 통지한다.
 *
 * <p>무인 실매매에서 "오늘 어떤 국면에서 어떤 전략이 실전 진입 가능한지"를 장 시작 시 한눈에 보기 위한 운영 알림.
 * {@link StartupNotifier}(앱 기동 시 태세)와 달리 <b>매 장 시작(평일 09:00)마다</b> 발송되며, 그 시점의
 * {@link MarketRegimeService} 국면과 {@link StrategyPerformanceGate} 판정(국면조건부 + fallback)을 반영한다.</p>
 *
 * <p>⚠️ 정적 화이트리스트({@code trading.live-strategies})는 <b>env·재기동으로만 변경</b>되므로 런타임 변경 알림은 없다
 * (변경하려면 .env 수정 후 재기동 → {@link StartupNotifier}가 새 화이트리스트를 통지). 게이트 통과 집합(실효 진입 가능)은
 * 표본·국면에 따라 상시 변하며, 그 스냅샷을 이 장시작 알림이 매일 제공한다.</p>
 */
@Service
public class MarketOpenNotifier {

    private static final Logger log = LoggerFactory.getLogger(MarketOpenNotifier.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd(E)", Locale.KOREAN);

    private final MarketRegimeService regimeService;
    private final StrategyPerformanceGate performanceGate;
    private final TradingPolicyProperties policy;
    private final DiscordNotifier discordNotifier;

    public MarketOpenNotifier(MarketRegimeService regimeService,
                              StrategyPerformanceGate performanceGate,
                              TradingPolicyProperties policy,
                              DiscordNotifier discordNotifier) {
        this.regimeService = regimeService;
        this.performanceGate = performanceGate;
        this.policy = policy;
        this.discordNotifier = discordNotifier;
    }

    /** 장 시작 국면 + 전략 태세 스냅샷을 Discord로 발송. 실패는 격리(스케줄 흐름 보호). */
    public void notifyMarketOpen() {
        try {
            discordNotifier.send(buildMessage());
        } catch (Exception e) {
            log.warn("장 시작 알림 발송 실패: {}", e.getMessage());
        }
    }

    /** 발송 메시지 조립(테스트 가능하도록 분리). */
    public String buildMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("📈 **장 시작** ").append(LocalDate.now(SEOUL).format(DATE)).append("\n");

        // ── 시초 국면(시장별 추세·변동성) ──
        for (MarketRegimeService.MarketRegime r : regimeService.all()) {
            sb.append(String.format("[국면] %s %s·%s%s%n",
                    r.market(), r.trend().korean(), r.volatility().korean(),
                    r.available() ? "" : "(데이터부족·기본값)"));
        }

        // ── 실전 매매 태세 ──
        String wl = (policy.liveStrategies() == null || policy.liveStrategies().isEmpty())
                ? "(비어있음)" : String.join(",", policy.liveStrategies());
        sb.append(String.format("[태세] 모드 %s / enabled %s / 수동승인 %s%n",
                policy.mode(), policy.enabled(), policy.manualConfirm()));
        sb.append("[정적 화이트리스트] ").append(wl).append("\n");

        // ── 게이트 통과(실효 진입 가능) — 시장별. fallback축소·화이트리스트 미포함 표시 ──
        List<StrategyPerformanceGate.GateDecision> gates = performanceGate.evaluateAll();
        sb.append("[게이트 통과]\n");
        for (String market : List.of("KOSPI", "KOSDAQ", "INVERSE")) {
            String passed = gates.stream()
                    .filter(g -> market.equals(g.market()) && g.allowed())
                    .map(g -> g.strategy()
                            + (g.fallback() ? "(fallback축소)" : "")
                            + (policy.isLiveAllowed(g.strategy()) ? "" : "*"))
                    .collect(Collectors.joining(", "));
            sb.append("  • ").append(market).append(": ").append(passed.isEmpty() ? "-" : passed).append("\n");
        }
        sb.append("  (* = 화이트리스트 미포함 → 게이트는 통과해도 실주문 안 나감)\n");
        sb.append(Disclaimer.SHORT);
        return sb.toString();
    }
}
