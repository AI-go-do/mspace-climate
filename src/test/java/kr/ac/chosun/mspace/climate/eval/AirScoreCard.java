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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import kr.ac.chosun.mspace.climate.domain.AnomalyDetector;
import kr.ac.chosun.mspace.climate.domain.Baseline;
import kr.ac.chosun.mspace.climate.domain.BaselineCalculator;
import kr.ac.chosun.mspace.climate.domain.Finding;
import kr.ac.chosun.mspace.climate.domain.Forecast;
import kr.ac.chosun.mspace.climate.domain.Metric;
import kr.ac.chosun.mspace.climate.domain.MetricHistory;
import kr.ac.chosun.mspace.climate.domain.MetricRule;
import kr.ac.chosun.mspace.climate.domain.Sample;
import kr.ac.chosun.mspace.climate.domain.TrendForecaster;

/**
 * 미리 환기 알림 채점표 (발표용, 실측만). 실습실 CO2 센서 3개의 실제 1분 기록(10/21~11/8, src/main/resources/replay)을
 * 1분씩 흘리며 climate와 같은 기준선·예측·이상 탐지·30분 알림 거름을 돌리고, 그 뒤 실제 값과 맞춰 본다.
 *
 * <p>정답(사람 메모 없이 기록 자체로): "넘음" = CO2가 1000ppm(FR-DEV-104) 이상이 된 첫 분, 앞 30분 동안은 1000 밑.
 * <ul>
 *   <li>미리 맞힘: 넘기 전 30분 안에 CO2 알림이 갔다(넘기 1분 이상 전)</li>
 *   <li>놓침: 넘을 때까지 알림이 없었다(넘은 뒤 5분 안 "넘음" 알림은 늦음으로 따로 셈)</li>
 *   <li>헛알림: 알림을 보냈는데 그 뒤 30분 안에 1000ppm에 닿지 않았다</li>
 * </ul>
 * 실행: {@code mvn -q test-compile && java -cp target/classes:target/test-classes:$(cat cp.txt) ...AirScoreCard [판이름]}
 * (README "미리 환기 채점표")
 */
public final class AirScoreCard {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final double LIMIT = 1000;
    /** 실습실 센서 자리 → 방 번호 (application.yml climate.mqtt.spots와 같음) */
    static final Map<String, Integer> SPOTS = Map.of("후면 오른쪽", 1, "전방 좌측", 2, "후방 우측", 3);

    /** 판 하나의 설정. 판마다 하나만 바꾼다 */
    public record Variant(String name, String what, double k, Duration trendWindow, int minTrendPoints, Duration lead,
                          Duration alertEvery, Duration offline, boolean fixedLimit, int confirmMinutes, double minSlope) {

        static Variant base() {
            return new Variant("0", "기준: 지금 dev 설정(방 기준선 경계, 추세 30분, 15분 전)", 2.5, Duration.ofMinutes(30), 5,
                    Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofMinutes(10), false, 0, 0);
        }

        Variant as(String n, String w) {
            return new Variant(n, w, k, trendWindow, minTrendPoints, lead, alertEvery, offline, fixedLimit, confirmMinutes, minSlope);
        }

        Variant lead(int m) {
            return new Variant(name, what, k, trendWindow, minTrendPoints, Duration.ofMinutes(m), alertEvery, offline, fixedLimit,
                    confirmMinutes, minSlope);
        }

        Variant window(int m) {
            return new Variant(name, what, k, Duration.ofMinutes(m), minTrendPoints, lead, alertEvery, offline, fixedLimit,
                    confirmMinutes, minSlope);
        }

        Variant confirm(int m) {
            return new Variant(name, what, k, trendWindow, minTrendPoints, lead, alertEvery, offline, fixedLimit, m, minSlope);
        }

        Variant fixed() {
            return new Variant(name, what, k, trendWindow, minTrendPoints, lead, alertEvery, offline, true, confirmMinutes, minSlope);
        }

        Variant slope(double ppm) {
            return new Variant(name, what, k, trendWindow, minTrendPoints, lead, alertEvery, offline, fixedLimit, confirmMinutes, ppm);
        }
    }

    /** 판 하나의 결과 */
    public record Score(Variant v, int crossings, int early, int late, int missed, int alerts, int falseAlerts,
                        double medianLeadMin, double alertsPerDay, List<String> missedAt) {
        // alerts·falseAlerts는 "미리 알림"(보낼 때 아직 1000 밑)만 센다. 이미 넘은 뒤 30분마다 다시 가는 알림은 맞고 틀림이 없어 뺀다

        double recall() {
            return crossings == 0 ? 0 : 100.0 * early / crossings;
        }

        double precision() {
            return alerts == 0 ? 0 : 100.0 * (alerts - falseAlerts) / alerts;
        }

        /** 균형 점수: 미리 맞힘률과 미리 알림 정확도가 둘 다 높아야 오른다(조화 평균, F1) */
        double balance() {
            double r = recall();
            double p = precision();
            return r + p == 0 ? 0 : 2 * r * p / (r + p);
        }
    }

    public static void main(String[] args) throws IOException {
        Map<Integer, TreeMap<Instant, Double>> data;
        try (InputStream in = AirScoreCard.class.getResourceAsStream("/replay/lab-1min.csv.gz")) {
            data = load(in);
        }
        List<Variant> rounds = rounds();
        System.out.println("| 판 | 바꾼 것 | 넘음 | 미리 맞힘 | 늦음 | 놓침 | 미리 알림 | 헛알림 | 미리 맞힘률 | 미리 알림 정확도 | 균형 점수 | 몇 분 전(중앙) | 방당 하루 알림 |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        for (Variant v : rounds) {
            if (args.length > 0 && !v.name().equals(args[0])) {
                continue;
            }
            Score s = score(data, v);
            System.out.printf("| %s | %s | %d | %d | %d | %d | %d | %d | %.0f%% | %.0f%% | %.0f | %.0f분 | %.1f |%n", v.name(), v.what(),
                    s.crossings(), s.early(), s.late(), s.missed(), s.alerts(), s.falseAlerts(), s.recall(), s.precision(),
                    s.balance(),
                    s.medianLeadMin(), s.alertsPerDay());
            if (args.length > 0) {
                s.missedAt().forEach(m -> System.out.println("  놓침 " + m));
            }
        }
    }

    /**
     * 판 목록. 판마다 앞 판에서 하나만 바꾼다. 점수가 나아지면 채택해 다음 판의 출발점으로, 아니면 탈락(출발점 그대로).
     * 고르는 기준: 놓침 0 유지 → 미리 맞힘률 → 미리 알림 정확도
     */
    static List<Variant> rounds() {
        List<Variant> out = new ArrayList<>();
        Variant base = Variant.base();
        out.add(base);
        Variant d = base.fixed().as("1", "경계를 방 기준선 대신 1000ppm 고정");
        out.add(d);
        out.add(d.lead(20).as("2", "1 + 미리 볼 시간 15→20분"));
        out.add(d.window(15).as("3", "1 + 추세 창 30→15분"));
        out.add(d.window(20).as("4", "1 + 추세 창 30→20분"));
        out.add(d.slope(3).as("5", "1 + 1분에 3ppm 넘게 오를 때만"));
        out.add(d.confirm(5).as("6", "1 + 최근 5분도 오를 때만"));
        out.add(d.window(15).confirm(5).as("7", "3 + 최근 5분도 오를 때만"));
        out.add(d.window(20).confirm(5).as("8", "4 + 최근 5분도 오를 때만"));
        out.add(d.lead(10).as("9", "1 + 미리 볼 시간 15→10분"));
        return out;
    }

    /** gzip CSV(minute_utc,spot,co2,...) → 방 번호 → 시각 → CO2 */
    static Map<Integer, TreeMap<Instant, Double>> load(InputStream gz) throws IOException {
        Map<Integer, TreeMap<Instant, Double>> by = new LinkedHashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(gz), StandardCharsets.UTF_8))) {
            String line = in.readLine();
            while ((line = in.readLine()) != null) {
                String[] f = line.split(",", -1);
                Integer room = f.length < 5 ? null : SPOTS.get(f[1]);
                if (room == null || f[2].isBlank()) {
                    continue;
                }
                Instant at = LocalDateTime.parse(f[0]).toInstant(ZoneOffset.UTC);
                by.computeIfAbsent(room, k -> new TreeMap<>()).put(at, Double.parseDouble(f[2]));
            }
        }
        return by;
    }

    static Score score(Map<Integer, TreeMap<Instant, Double>> data, Variant v) {
        MetricRule rule = new MetricRule(null, LIMIT, 150, 400, v.fixedLimit(), null);
        BaselineCalculator baselines = new BaselineCalculator(SEOUL, v.k(), 30);
        TrendForecaster forecaster = new TrendForecaster(v.trendWindow(), v.minTrendPoints());
        AnomalyDetector detector = new AnomalyDetector(v.lead(), Duration.ofMinutes(20));
        int crossings = 0;
        int early = 0;
        int late = 0;
        int missed = 0;
        int alerts = 0;
        int falseAlerts = 0;
        int allAlerts = 0;
        List<Double> leads = new ArrayList<>();
        List<String> missedAt = new ArrayList<>();
        long minutes = 0;
        for (var e : data.entrySet()) {
            TreeMap<Instant, Double> series = e.getValue();
            MetricHistory history = new MetricHistory(Duration.ofDays(7));
            List<Instant> sent = new ArrayList<>();
            List<Instant> pre = new ArrayList<>();
            Instant first = series.firstKey();
            Instant last = series.lastKey();
            minutes += Duration.between(first, last).toMinutes();
            Instant lastAlert = null;
            for (Instant now = first; !now.isAfter(last); now = now.plusSeconds(60)) {
                Double value = series.get(now);
                if (value != null) {
                    history.add(now, value);
                }
                Sample latest = history.latest();
                if (latest == null || latest.at().plus(v.offline()).isBefore(now)) {
                    continue; // 끊김: climate도 이 방은 판단하지 않음
                }
                List<Sample> h = history.all();
                Baseline b = baselines.baseline(h, now, rule);
                Forecast f = forecaster.forecast(h, b, now);
                boolean high = false;
                for (Finding x : detector.detect(Metric.CO2, h, b, f, rule, now)) {
                    high |= x.kind() == Finding.Kind.OVER
                            || x.kind() == Finding.Kind.RISING_SOON && f.slopePerMinute() >= v.minSlope();
                }
                if (high && v.confirmMinutes() > 0 && !risingFor(h, now, v.confirmMinutes())
                        && latest.value() < b.upper()) {
                    high = false;
                }
                if (high && (lastAlert == null || !lastAlert.plus(v.alertEvery()).isAfter(now))) {
                    lastAlert = now;
                    sent.add(now);
                    if (latest.value() < LIMIT) {
                        pre.add(now);
                    }
                }
            }
            // 정답: 1000 넘은 첫 분(앞 30분은 1000 밑)
            List<Instant> cross = new ArrayList<>();
            Instant lastHigh = null;
            for (var s : series.entrySet()) {
                if (s.getValue() >= LIMIT) {
                    if (lastHigh == null || lastHigh.plus(Duration.ofMinutes(30)).isBefore(s.getKey())) {
                        cross.add(s.getKey());
                    }
                    lastHigh = s.getKey();
                }
            }
            crossings += cross.size();
            for (Instant c : cross) {
                Instant before = sent.stream().filter(a -> !a.isBefore(c.minus(Duration.ofMinutes(30)))
                        && !a.isAfter(c.minusSeconds(60))).findFirst().orElse(null);
                if (before != null) {
                    early++;
                    leads.add((double) Duration.between(before, c).toMinutes());
                } else if (sent.stream().anyMatch(a -> !a.isBefore(c) && !a.isAfter(c.plus(Duration.ofMinutes(5))))) {
                    late++;
                } else {
                    missed++;
                    missedAt.add(e.getKey() + "번 방 " + c.atZone(SEOUL).toLocalDateTime());
                }
            }
            alerts += pre.size();
            allAlerts += sent.size();
            for (Instant a : pre) {
                boolean reached = series.subMap(a, true, a.plus(Duration.ofMinutes(30)), true).values().stream()
                        .anyMatch(x -> x >= LIMIT);
                if (!reached) {
                    falseAlerts++;
                }
            }
        }
        leads.sort(Double::compare);
        double median = leads.isEmpty() ? 0 : leads.get(leads.size() / 2);
        double days = minutes / 1440.0 / Math.max(1, data.size());
        return new Score(v, crossings, early, late, missed, alerts, falseAlerts, median,
                days == 0 ? 0 : allAlerts / days / Math.max(1, data.size()), missedAt);
    }

    /** 최근 n분 동안 값이 계속 올랐나(1분 값끼리, 같거나 오름) */
    static boolean risingFor(List<Sample> h, Instant now, int n) {
        Instant from = now.minus(Duration.ofMinutes(n));
        List<Sample> w = h.stream().filter(s -> !s.at().isBefore(from)).toList();
        if (w.size() < 2) {
            return false;
        }
        return w.get(w.size() - 1).value() - w.get(0).value() > 0;
    }
}
