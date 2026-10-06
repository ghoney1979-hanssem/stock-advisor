package com.stockadvisor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 선정 품질 <b>사전등록 규칙</b> — "진입분 중 feature가 하위 컷 이하인 쪽 vs 상위 컷 이상인 쪽"의 D+N 초과수익 차이를
 * 표본 내에서 측정해 기록하고, <b>등록 후 쌓인 표본 밖 데이터로만</b> 판정한다.
 *
 * <p>⚠️ <b>수정 API가 없는 것이 설계다</b> — 결과를 본 뒤 컷을 바꾸면 사전등록이 무의미해진다(2026-10-01 결정).
 * 바꾸려면 새 이름으로 다시 등록하고 다시 기다린다. 이 시스템은 수치를 본 뒤 해석 기준을 만드는 실수를
 * 여러 번 반복했다(클러스터 함정 6회, holdout 6회 소진).</p>
 */
@Entity
@Table(name = "selection_prereg", indexes = {
        @Index(name = "idx_selection_prereg_name", columnList = "name", unique = true)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SelectionPrereg {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 60, nullable = false)
    private String name;

    /** 전략명, 또는 {@code *}(전 전략 — 같은 전략·같은 날끼리 매칭). */
    @Column(name = "strategy", length = 40, nullable = false)
    private String strategy;

    /** {@code SelectionQualityService.FEATURES}의 키. */
    @Column(name = "feature", length = 20, nullable = false)
    private String feature;

    /** 판정 지평(거래일) — 5/10/15. */
    @Column(name = "horizon", nullable = false)
    private int horizon;

    @Column(name = "lo_cut", nullable = false)
    private double loCut;

    @Column(name = "hi_cut", nullable = false)
    private double hiCut;

    /** 표본 내 상위−하위(%p, 같은 날 매칭). 표본 밖 판정의 기준(부호·크기 1/3). */
    @Column(name = "in_sample_diff_pct", nullable = false)
    private double inSampleDiffPct;

    @Column(name = "in_sample_from", length = 8, nullable = false)
    private String inSampleFrom;

    @Column(name = "in_sample_to", length = 8, nullable = false)
    private String inSampleTo;

    /** 표본 밖 시작 진입일(이 날 이후 진입분만 판정에 쓴다). */
    @Column(name = "oos_from", length = 8, nullable = false)
    private String oosFrom;

    @Column(name = "min_days", nullable = false)
    private int minDays;

    /** 통과 시 실행안(사람이 읽는 문장). */
    @Column(name = "action", length = 200, nullable = false)
    private String action;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    public SelectionPrereg(String name, String strategy, String feature, int horizon, double loCut, double hiCut,
                           double inSampleDiffPct, String inSampleFrom, String inSampleTo, String oosFrom,
                           int minDays, String action) {
        this.name = name;
        this.strategy = strategy;
        this.feature = feature;
        this.horizon = horizon;
        this.loCut = loCut;
        this.hiCut = hiCut;
        this.inSampleDiffPct = inSampleDiffPct;
        this.inSampleFrom = inSampleFrom;
        this.inSampleTo = inSampleTo;
        this.oosFrom = oosFrom;
        this.minDays = minDays;
        this.action = action;
        this.registeredAt = Instant.now();
    }
}
