package com.stockadvisor.service;

import com.stockadvisor.common.Disclaimer;
import com.stockadvisor.notification.DiscordNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 게이트 통과 집합(실효 진입 가능) 실시간 변화 알림.
 *
 * <p>{@link StrategyPerformanceGate#evaluateAll()}의 (시장×전략) 판정을 주기적으로 스냅샷해, 직전 스냅샷 대비
 * <b>상태 전이가 생긴 항목만</b> Discord로 통지한다. 상태는 3단계:
 * <ul>
 *   <li>{@code CLOSED} — 게이트 차단(진입 불가)</li>
 *   <li>{@code LIVE} — 게이트 통과(정상 사이징 진입)</li>
 *   <li>{@code FALLBACK} — 국면무관 fallback 통과(축소 사이징 진입)</li>
 * </ul>
 * 전이 예: 차단→진입가능(🟢), 진입가능→차단(🔴), 정상↔fallback축소(🔁).</p>
 *
 * <p>상태는 <b>인메모리</b>라 앱 재기동 시 baseline이 리셋된다 — 재기동 후 첫 스냅샷은 <b>알림 없이</b> 기준선만
 * 확립(재기동마다 전체 집합을 다시 쏘는 스팸 방지). 재기동 시점 태세는 {@link StartupNotifier}·{@link MarketOpenNotifier}가
 * 이미 통지하므로 중복되지 않는다. 장 시작 09:00 스냅샷({@link MarketOpenNotifier})과 상호보완: 이건 <b>장중 변화</b>만.</p>
 */
@Service
public class GateChangeNotifier {

    private static final Logger log = LoggerFactory.getLogger(GateChangeNotifier.class);

    static final String CLOSED = "CLOSED";
    static final String LIVE = "LIVE";
    static final String FALLBACK = "FALLBACK";

    private final StrategyPerformanceGate performanceGate;
    private final DiscordNotifier discordNotifier;

    /** 직전 스냅샷 — "MARKET:STRATEGY" → 상태. null이면 baseline 미확립(첫 스냅샷은 알림 없이 기준선만). */
    private Map<String, String> last = null;

    public GateChangeNotifier(StrategyPerformanceGate performanceGate, DiscordNotifier discordNotifier) {
        this.performanceGate = performanceGate;
        this.discordNotifier = discordNotifier;
    }

    /** 현재 게이트 집합을 스냅샷해 직전 대비 변화가 있으면 통지. 실패는 격리(스케줄 흐름 보호). */
    public synchronized void checkAndNotify() {
        try {
            Map<String, String> current = snapshot();
            if (last == null) {          // 재기동 후 첫 스냅샷 → 기준선만 확립(알림 X)
                last = current;
                log.info("게이트 변화 감시 기준선 확립 ({}개 항목)", current.size());
                return;
            }
            List<String> changes = computeChanges(last, current);
            if (!changes.isEmpty()) {
                discordNotifier.send(buildMessage(changes));
            }
            last = current;
        } catch (Exception e) {
            log.warn("게이트 변화 알림 처리 실패: {}", e.getMessage());
        }
    }

    /**
     * 테스트/수동: 현재 게이트 통과 집합을 알림으로 즉시 발송(전이 무관, baseline 갱신 안 함).
     * 실시간 전이 알림({@link #checkAndNotify()})과 별개로 "지금 무엇이 진입 가능한지"를 확인 발송한다.
     */
    public String sendTestAlert() {
        Map<String, String> cur = snapshot();
        StringBuilder sb = new StringBuilder("🔔 **[테스트] 게이트 통과 집합** (현재 실효 진입 가능)\n");
        boolean any = false;
        for (Map.Entry<String, String> e : cur.entrySet()) {
            if (CLOSED.equals(e.getValue())) continue;   // 통과(진입가능)만 표시
            String[] mk = e.getKey().split(":", 2);
            sb.append(String.format("  • %s·%s: %s%n", mk[0], mk[1], korean(e.getValue())));
            any = true;
        }
        if (!any) sb.append("  (현재 게이트 통과 전략 없음)\n");
        sb.append(Disclaimer.SHORT);
        String msg = sb.toString();
        discordNotifier.send(msg);
        return msg;
    }

    /** (시장×전략) → 상태 스냅샷. */
    Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        for (StrategyPerformanceGate.GateDecision d : performanceGate.evaluateAll()) {
            m.put(d.market() + ":" + d.strategy(), stateOf(d));
        }
        return m;
    }

    private String stateOf(StrategyPerformanceGate.GateDecision d) {
        if (!d.allowed()) return CLOSED;
        return d.fallback() ? FALLBACK : LIVE;
    }

    /** 직전→현재 상태 전이가 생긴 항목의 사람이 읽는 설명 목록(변화 없으면 빈 목록). */
    List<String> computeChanges(Map<String, String> prev, Map<String, String> cur) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : cur.entrySet()) {
            String key = e.getKey();
            String to = e.getValue();
            String from = prev.getOrDefault(key, CLOSED);   // 신규 키는 CLOSED에서 시작한 것으로 간주
            if (from.equals(to)) continue;
            out.add(describe(key, from, to));
        }
        // 현재 스냅샷에서 사라진 키(전략 제거 등)는 무시 — evaluateAll은 등록 전략을 항상 포함하므로 통상 발생 안 함.
        return out;
    }

    private String describe(String key, String from, String to) {
        String icon;
        if (CLOSED.equals(from)) icon = "🟢";           // 새로 열림
        else if (CLOSED.equals(to)) icon = "🔴";        // 닫힘
        else icon = "🔁";                               // LIVE ↔ FALLBACK
        String[] mk = key.split(":", 2);
        String label = mk[0] + "·" + mk[1];             // 시장·전략
        return String.format("%s %s: %s → %s", icon, label, korean(from), korean(to));
    }

    private String korean(String state) {
        return switch (state) {
            case LIVE -> "진입가능";
            case FALLBACK -> "진입가능(fallback축소)";
            default -> "차단";
        };
    }

    private String buildMessage(List<String> changes) {
        StringBuilder sb = new StringBuilder("🔔 **게이트 변화** (실효 진입 가능 집합)\n");
        for (String c : changes) sb.append("  ").append(c).append("\n");
        sb.append(Disclaimer.SHORT);
        return sb.toString();
    }
}
