package kr.ac.chosun.mspace.climate.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 최근 N분(기본 30분) 1분 값에 직선을 맞춰(최소제곱) 1분당 변화를 재고, 그 추세로 경계에 닿을 때까지 남은 분을 낸다.
 * 가볍고 설명하기 쉬워 첫 판으로 쓴다(발표 "기준 → 개선" 표의 기준 방식).
 */
public final class TrendForecaster implements Forecaster {

    static final int HORIZON_MINUTES = 30;
    static final int STEP_MINUTES = 5;
    /** 이 정도보다 느린 변화는 "그대로"로 본다(센서 잡음) */
    static final double FLAT = 1e-6;

    private final Duration window;
    private final int minPoints;

    public TrendForecaster(Duration window, int minPoints) {
        this.window = window;
        this.minPoints = minPoints;
    }

    @Override
    public Forecast forecast(List<Sample> recent, Baseline baseline, Instant now) {
        Instant from = now.minus(window);
        List<Sample> pts = recent.stream().filter(s -> !s.at().isBefore(from)).toList();
        if (pts.size() < minPoints) {
            return Forecast.none();
        }
        // x = 지금 기준 분(음수), y = 값
        double n = pts.size();
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double sxy = 0;
        for (Sample s : pts) {
            double x = Duration.between(now, s.at()).toSeconds() / 60.0;
            sx += x;
            sy += s.value();
            sxx += x * x;
            sxy += x * s.value();
        }
        double den = n * sxx - sx * sx;
        if (Math.abs(den) < 1e-9) {
            return Forecast.none();
        }
        double slope = (n * sxy - sx * sy) / den;
        // 예측선은 지금 값에서 시작한다. 직선의 지금 시각 값(절편)에서 그리면, 오래 평평하다 막 오르기 시작한 방은
        // 절편이 지금 값보다 낮아 "오르는데 예측은 내려간다"처럼 보인다(관제 화면 11/12). 닿는 시간(eta)도 지금 값 기준이라 둘이 맞는다
        double current = pts.get(pts.size() - 1).value();
        List<Sample> points = new ArrayList<>();
        for (int m = STEP_MINUTES; m <= HORIZON_MINUTES; m += STEP_MINUTES) {
            points.add(new Sample(now.plus(Duration.ofMinutes(m)), BaselineCalculator.round(current + slope * m)));
        }
        Integer eta = null;
        Forecast.Boundary towards = null;
        if (slope > FLAT && baseline.upper() != null && current < baseline.upper()) {
            eta = (int) Math.ceil((baseline.upper() - current) / slope - 1e-6);
            towards = Forecast.Boundary.UPPER;
        } else if (slope < -FLAT && baseline.lower() != null && current > baseline.lower()) {
            eta = (int) Math.ceil((current - baseline.lower()) / -slope - 1e-6);
            towards = Forecast.Boundary.LOWER;
        }
        return new Forecast(Math.round(slope * 100) / 100.0, eta, towards, points);
    }
}
