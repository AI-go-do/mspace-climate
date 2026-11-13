package kr.ac.chosun.mspace.climate.domain;

import java.util.List;

/**
 * 예측. slopePerMinute는 앞으로의 1분당 변화, etaMinutes는 경계에 닿기까지 남은 분(멀어지는 중이거나 이미 넘었으면 null).
 * points는 앞으로 30분을 5분 간격으로 그린 예측선(그래프 점선). method는 누가 예측했나: TREND(최근 30분 직선) / GRU(실습 시계열 모델)
 */
public record Forecast(double slopePerMinute, Integer etaMinutes, Boundary towards, List<Sample> points, String method) {

    public enum Boundary { UPPER, LOWER }

    public static final String TREND = "TREND";
    public static final String GRU = "GRU";

    public Forecast(double slopePerMinute, Integer etaMinutes, Boundary towards, List<Sample> points) {
        this(slopePerMinute, etaMinutes, towards, points, TREND);
    }

    public static Forecast none() {
        return new Forecast(0, null, null, List.of());
    }
}
