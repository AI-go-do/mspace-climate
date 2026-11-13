package kr.ac.chosun.mspace.climate.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 모델(GRU) 예측선에 최근 30분 직선을 조금(weight) 섞는다. GRU 혼자는 아침 수업 시작 때 빠르게 오르는 CO2를 너무 멀리 끌어올려
 * 헛알림이 났다. 직선 25%를 섞으면 맞추기용 8주에서 헛알림 13→9번, 30분 뒤 오차도 줄었다 (README 예측 모델 비교 16판).
 * 모델이 못 낸 예측(직선으로 돌아간 것)은 그대로 둔다.
 */
public final class BlendForecaster implements Forecaster {

    private final Forecaster model;
    private final Forecaster trend;
    private final double weight;

    public BlendForecaster(Forecaster model, Forecaster trend, double weight) {
        this.model = model;
        this.trend = trend;
        this.weight = weight;
    }

    @Override
    public Forecast forecast(List<Sample> recent, Baseline baseline, Instant now) {
        return trend.forecast(recent, baseline, now);
    }

    @Override
    public Forecast forecast(Metric metric, Map<Metric, List<Sample>> history, Baseline b, Instant now) {
        Forecast g = model.forecast(metric, history, b, now);
        List<Sample> own = history.getOrDefault(metric, List.of());
        if (!Forecast.GRU.equals(g.method()) || g.points().isEmpty() || own.isEmpty()) {
            return g;
        }
        Forecast t = trend.forecast(metric, history, b, now);
        double current = own.get(own.size() - 1).value();
        List<Sample> points = new ArrayList<>();
        double[] at = new double[g.points().size()];
        double[] value = new double[at.length];
        for (int k = 0; k < at.length; k++) {
            Sample p = g.points().get(k);
            at[k] = Duration.between(now, p.at()).toSeconds() / 60.0;
            value[k] = (1 - weight) * p.value() + weight * (current + t.slopePerMinute() * at[k]);
            points.add(new Sample(p.at(), Math.round(value[k] * 100) / 100.0));
        }
        Integer eta = null;
        Forecast.Boundary towards = null;
        if (b.upper() != null && current < b.upper()) {
            eta = cross(at, value, current, b.upper(), true);
            towards = eta == null ? null : Forecast.Boundary.UPPER;
        }
        if (eta == null && b.lower() != null && current > b.lower()) {
            eta = cross(at, value, current, b.lower(), false);
            towards = eta == null ? null : Forecast.Boundary.LOWER;
        }
        return new Forecast(g.slopePerMinute(), eta, towards, points, Forecast.GRU);
    }

    /** 지금(0분, current)에서 점들을 직선으로 이어 처음 경계에 닿는 분 (올림) */
    private static Integer cross(double[] at, double[] value, double current, double limit, boolean up) {
        double pt = 0;
        double pv = current;
        for (int k = 0; k < at.length; k++) {
            if (up ? value[k] >= limit : value[k] <= limit) {
                return (int) Math.ceil(pt + (limit - pv) / (value[k] - pv) * (at[k] - pt) - 1e-6);
            }
            pt = at[k];
            pv = value[k];
        }
        return null;
    }
}
