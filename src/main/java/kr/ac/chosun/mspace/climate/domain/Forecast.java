package kr.ac.chosun.mspace.climate.domain;

import java.util.List;

/**
 * 추세 예측. slopePerMinute는 최근 값으로 잰 1분당 변화, etaMinutes는 지금 추세로 경계에 닿기까지 남은 분(멀어지는 중이거나 이미 넘었으면 null).
 * points는 앞으로 30분을 5분 간격으로 그린 예측선(그래프 점선).
 */
public record Forecast(double slopePerMinute, Integer etaMinutes, Boundary towards, List<Sample> points) {

    public enum Boundary { UPPER, LOWER }

    public static Forecast none() {
        return new Forecast(0, null, null, List.of());
    }
}
