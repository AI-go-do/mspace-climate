package kr.ac.chosun.mspace.climate.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * 방마다 실측값으로 기준선을 잡는다 (재웅 11/12: "실시간 센서값으로 평균을 잡아 방마다 기준점").
 * 같은 방이라도 오후 3시와 새벽 3시는 다르니, 먼저 "같은 시간대(±1시간)" 값만으로 평균·흩어짐을 낸다.
 * 시간대 값이 모자라면 전체 값으로, 그것도 모자라면 안전 한계만 쓴다(learning).
 */
public final class BaselineCalculator {

    private final ZoneId zone;
    private final double k;
    private final int minSamples;

    /**
     * @param k          경계 = 평균 ± k × 흩어짐
     * @param minSamples 평균을 믿기 위한 최소 1분 값 개수
     */
    public BaselineCalculator(ZoneId zone, double k, int minSamples) {
        this.zone = zone;
        this.k = k;
        this.minSamples = minSamples;
    }

    public Baseline baseline(List<Sample> history, Instant now, MetricRule rule) {
        int hour = now.atZone(zone).getHour();
        Stats hourly = Stats.of(history.stream()
                .filter(s -> nearHour(s.at().atZone(zone).getHour(), hour))
                .mapToDouble(Sample::value).toArray());
        if (hourly.n >= minSamples) {
            return bounded(hourly, rule, "HOUR");
        }
        Stats all = Stats.of(history.stream().mapToDouble(Sample::value).toArray());
        if (all.n >= minSamples) {
            return bounded(all, rule, "ALL");
        }
        Double mean = all.n == 0 ? null : all.mean;
        return new Baseline(mean, null, rule.hardMin(), rule.hardMax(), all.n, true, all.n == 0 ? "NONE" : "ALL");
    }

    private Baseline bounded(Stats s, MetricRule rule, String scope) {
        double band = Math.max(k * s.std, rule.minBand());
        double upper = s.mean + band;
        if (rule.hardMax() != null) {
            upper = rule.fixedUpper() ? rule.hardMax() : Math.min(upper, rule.hardMax());
        }
        Double lower = null;
        if (rule.hardMin() != null) {
            lower = Math.max(s.mean - band, rule.hardMin());
        }
        return new Baseline(round(s.mean), round(s.std), lower == null ? null : round(lower), round(upper), s.n, false,
                scope);
    }

    private static boolean nearHour(int h, int hour) {
        int d = Math.abs(h - hour);
        return Math.min(d, 24 - d) <= 1;
    }

    static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private record Stats(int n, double mean, double std) {
        static Stats of(double[] v) {
            if (v.length == 0) {
                return new Stats(0, 0, 0);
            }
            double sum = 0;
            for (double x : v) {
                sum += x;
            }
            double mean = sum / v.length;
            double sq = 0;
            for (double x : v) {
                sq += (x - mean) * (x - mean);
            }
            return new Stats(v.length, mean, Math.sqrt(sq / v.length));
        }
    }
}
