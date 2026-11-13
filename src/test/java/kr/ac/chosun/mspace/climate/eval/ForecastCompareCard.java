package kr.ac.chosun.mspace.climate.eval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import kr.ac.chosun.mspace.climate.adapter.out.model.GruForecaster;
import kr.ac.chosun.mspace.climate.domain.AnomalyDetector;
import kr.ac.chosun.mspace.climate.domain.Baseline;
import kr.ac.chosun.mspace.climate.domain.BaselineCalculator;
import kr.ac.chosun.mspace.climate.domain.Finding;
import kr.ac.chosun.mspace.climate.domain.Forecast;
import kr.ac.chosun.mspace.climate.domain.Forecaster;
import kr.ac.chosun.mspace.climate.domain.Metric;
import kr.ac.chosun.mspace.climate.domain.MetricHistory;
import kr.ac.chosun.mspace.climate.domain.MetricRule;
import kr.ac.chosun.mspace.climate.domain.Sample;
import kr.ac.chosun.mspace.climate.domain.TrendForecaster;

/**
 * 예측 모델 비교 (재웅 11/13 "실습 시계열 모델 가져와서 써 보자"). 실습실 실측 1분 기록(10/21~11/8, 센서 3개)을
 * 1분씩 흘리며 지금 dev 설정(1000ppm 고정 경계, 15분 전 알림, 30분 거름)으로 두 예측을 똑같이 돌린다.
 * <ul>
 *   <li>직선(TREND): 최근 30분 직선</li>
 *   <li>GRU: 실습 시계열 모델(과제 3, 학습 2025-09-03~2026-08-26, 이 기록은 학습·검증·시험 어디에도 안 쓴 기간)</li>
 * </ul>
 * 점수는 AirScoreCard와 같은 정답(1000ppm 넘은 첫 분)으로 미리 맞힘·놓침·헛알림, 그리고 5분마다 15분·30분 뒤 CO2 오차(평균 절대 오차).
 * 실행: {@code java -cp target/classes:target/test-classes:$(cat cp.txt) kr.ac.chosun.mspace.climate.eval.ForecastCompareCard}
 */
public final class ForecastCompareCard {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final double LIMIT = 1000;
    static final Map<String, Integer> SPOTS = Map.of("후면 오른쪽", 1, "전방 좌측", 2, "후방 우측", 3);

    record Score(String name, int crossings, int early, int late, int missed, int alerts, int falseAlerts,
                 double medianLead, double mae15, double mae30, int gruShare, int judged) {

        double recall() {
            return crossings == 0 ? 0 : 100.0 * early / crossings;
        }

        double precision() {
            return alerts == 0 ? 0 : 100.0 * (alerts - falseAlerts) / alerts;
        }

        double balance() {
            double r = recall();
            double p = precision();
            return r + p == 0 ? 0 : 2 * r * p / (r + p);
        }
    }

    public static void main(String[] args) throws IOException {
        Map<Integer, Map<Metric, TreeMap<Instant, Double>>> data;
        try (InputStream in = ForecastCompareCard.class.getResourceAsStream("/replay/lab-1min.csv.gz")) {
            data = load(in);
        }
        TrendForecaster trend = new TrendForecaster(Duration.ofMinutes(30), 5);
        GruForecaster gru = GruForecaster.load(ForecastCompareCard.class.getClassLoader(), trend, SEOUL);
        if (gru == null) {
            throw new IllegalStateException("모델 파일 없음");
        }
        System.out.println("| 예측 | 넘음 | 미리 맞힘 | 늦음 | 놓침 | 미리 알림 | 헛알림 | 미리 맞힘률 | 미리 알림 정확도 | 균형 점수 | 몇 분 전(중앙) | 15분 뒤 CO2 오차 | 30분 뒤 CO2 오차 | 모델이 낸 비율 |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        // 판마다 하나만 바꾼다: 0 직선(지금) → 1 GRU → 2 GRU + 직선도 오를 때만 → 3 GRU + 15분 뒤 값도 넘을 때만
        Forecaster both = new Forecaster() {
            @Override
            public Forecast forecast(List<Sample> recent, Baseline b, Instant now) {
                return trend.forecast(recent, b, now);
            }

            @Override
            public Forecast forecast(Metric m, Map<Metric, List<Sample>> h, Baseline b, Instant now) {
                Forecast g = gru.forecast(m, h, b, now);
                Forecast t = trend.forecast(m, h, b, now);
                return t.slopePerMinute() > 0 ? g : new Forecast(g.slopePerMinute(), null, null, g.points(), g.method());
            }
        };
        Map<String, Forecaster> rounds = new LinkedHashMap<>();
        rounds.put("0 직선(지금)", trend);
        rounds.put("1 GRU", gru);
        rounds.put("2 GRU + 최근 30분 직선도 오를 때만", both);
        rounds.put("3 GRU + 미리 볼 시간 15→10분", gru);
        rounds.put("4 GRU + 미리 볼 시간 15→20분", gru);
        rounds.put("5 (공정 비교) 직선 + 미리 볼 시간 15→10분", trend);
        for (var e : rounds.entrySet()) {
            if (args.length > 0 && !e.getKey().startsWith(args[0])) {
                continue;
            }
            long t0 = System.nanoTime();
            int lead = e.getKey().contains("→10분") ? 10 : e.getKey().contains("→20분") ? 20 : 15;
            Score s = score(data, e.getKey(), e.getValue(), Duration.ofMinutes(lead));
            System.out.printf("| %s | %d | %d | %d | %d | %d | %d | %.0f%% | %.0f%% | %.0f | %.0f분 | %.1fppm | %.1fppm | %.0f%% |%n",
                    s.name(), s.crossings(), s.early(), s.late(), s.missed(), s.alerts(), s.falseAlerts(), s.recall(),
                    s.precision(), s.balance(), s.medianLead(), s.mae15(), s.mae30(),
                    s.judged() == 0 ? 0 : 100.0 * s.gruShare() / s.judged());
            System.err.printf("%s %.1f초%n", s.name(), (System.nanoTime() - t0) / 1e9);
        }
    }

    static Map<Integer, Map<Metric, TreeMap<Instant, Double>>> load(InputStream gz) throws IOException {
        Map<Integer, Map<Metric, TreeMap<Instant, Double>>> by = new LinkedHashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(gz), StandardCharsets.UTF_8))) {
            String line = in.readLine();
            while ((line = in.readLine()) != null) {
                String[] f = line.split(",", -1);
                Integer room = f.length < 5 ? null : SPOTS.get(f[1]);
                if (room == null) {
                    continue;
                }
                Instant at = LocalDateTime.parse(f[0]).toInstant(ZoneOffset.UTC);
                Map<Metric, TreeMap<Instant, Double>> m = by.computeIfAbsent(room, k -> new EnumMap<>(Metric.class));
                Metric[] order = {Metric.CO2, Metric.TEMPERATURE, Metric.HUMIDITY};
                for (int i = 0; i < 3; i++) {
                    if (!f[2 + i].isBlank()) {
                        m.computeIfAbsent(order[i], k -> new TreeMap<>()).put(at, Double.parseDouble(f[2 + i]));
                    }
                }
            }
        }
        return by;
    }

    static Score score(Map<Integer, Map<Metric, TreeMap<Instant, Double>>> data, String name, Forecaster forecaster,
                       Duration lead) {
        MetricRule rule = new MetricRule(null, LIMIT, 150, 400, true, null);
        BaselineCalculator baselines = new BaselineCalculator(SEOUL, 2.5, 30);
        AnomalyDetector detector = new AnomalyDetector(lead, Duration.ofMinutes(20));
        Duration every = Duration.ofMinutes(30);
        int crossings = 0;
        int early = 0;
        int late = 0;
        int missed = 0;
        int alerts = 0;
        int falseAlerts = 0;
        int gruShare = 0;
        int judged = 0;
        List<Double> leads = new ArrayList<>();
        double err15 = 0;
        double err30 = 0;
        int n15 = 0;
        int n30 = 0;
        for (var room : data.values()) {
            TreeMap<Instant, Double> co2 = room.get(Metric.CO2);
            Map<Metric, MetricHistory> hist = new EnumMap<>(Metric.class);
            for (Metric m : Metric.values()) {
                hist.put(m, new MetricHistory(Duration.ofDays(7)));
            }
            List<Instant> sent = new ArrayList<>();
            List<Instant> pre = new ArrayList<>();
            Instant lastAlert = null;
            for (Instant now = co2.firstKey(); !now.isAfter(co2.lastKey()); now = now.plusSeconds(60)) {
                for (var m : room.entrySet()) {
                    Double v = m.getValue().get(now);
                    if (v != null) {
                        hist.get(m.getKey()).add(now, v);
                    }
                }
                Sample latest = hist.get(Metric.CO2).latest();
                if (latest == null || latest.at().plus(Duration.ofMinutes(10)).isBefore(now)) {
                    continue;
                }
                // climate는 값이 들어온 분 끝 무렵에 판단한다(1분 값의 시각은 그 분의 시작)
                Instant at = now.plusSeconds(50);
                Map<Metric, List<Sample>> h = new EnumMap<>(Metric.class);
                hist.forEach((k, v) -> h.put(k, v.all()));
                List<Sample> c = h.get(Metric.CO2);
                Baseline b = baselines.baseline(c, at, rule);
                Forecast f = forecaster.forecast(Metric.CO2, h, b, at);
                judged++;
                gruShare += Forecast.GRU.equals(f.method()) ? 1 : 0;
                boolean high = false;
                for (Finding x : detector.detect(Metric.CO2, c, b, f, rule, at)) {
                    high |= x.kind() == Finding.Kind.OVER || x.kind() == Finding.Kind.RISING_SOON;
                }
                if (high && (lastAlert == null || !lastAlert.plus(every).isAfter(now))) {
                    lastAlert = now;
                    sent.add(now);
                    if (latest.value() < LIMIT) {
                        pre.add(now);
                    }
                }
                // 5분마다 15·30분 뒤 오차 (예측선 점 중 그 시각에 가장 가까운 점, 실제 1분 값과)
                if (now.getEpochSecond() / 60 % 5 == 0 && !f.points().isEmpty()) {
                    for (Sample p : f.points()) {
                        double ahead = Duration.between(at, p.at()).toSeconds() / 60.0;
                        Double actual = co2.get(p.at().truncatedTo(java.time.temporal.ChronoUnit.MINUTES));
                        if (actual == null) {
                            continue;
                        }
                        if (Math.abs(ahead - 15) < 2.5) {
                            err15 += Math.abs(p.value() - actual);
                            n15++;
                        } else if (Math.abs(ahead - 30) < 2.5) {
                            err30 += Math.abs(p.value() - actual);
                            n30++;
                        }
                    }
                }
            }
            List<Instant> cross = new ArrayList<>();
            Instant lastHigh = null;
            for (var s : co2.entrySet()) {
                if (s.getValue() >= LIMIT) {
                    if (lastHigh == null || lastHigh.plus(Duration.ofMinutes(30)).isBefore(s.getKey())) {
                        cross.add(s.getKey());
                    }
                    lastHigh = s.getKey();
                }
            }
            crossings += cross.size();
            for (Instant x : cross) {
                Instant before = sent.stream().filter(a -> !a.isBefore(x.minus(Duration.ofMinutes(30)))
                        && !a.isAfter(x.minusSeconds(60))).findFirst().orElse(null);
                if (before != null) {
                    early++;
                    leads.add((double) Duration.between(before, x).toMinutes());
                } else if (sent.stream().anyMatch(a -> !a.isBefore(x) && !a.isAfter(x.plus(Duration.ofMinutes(5))))) {
                    late++;
                } else {
                    missed++;
                }
            }
            alerts += pre.size();
            for (Instant a : pre) {
                if (co2.subMap(a, true, a.plus(Duration.ofMinutes(30)), true).values().stream().noneMatch(x -> x >= LIMIT)) {
                    falseAlerts++;
                }
            }
        }
        leads.sort(Double::compare);
        return new Score(name, crossings, early, late, missed, alerts, falseAlerts,
                leads.isEmpty() ? 0 : leads.get(leads.size() / 2), n15 == 0 ? 0 : err15 / n15, n30 == 0 ? 0 : err30 / n30,
                gruShare, judged);
    }
}
