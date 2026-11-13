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
import kr.ac.chosun.mspace.climate.domain.BlendForecaster;
import kr.ac.chosun.mspace.climate.domain.Finding;
import kr.ac.chosun.mspace.climate.domain.Forecast;
import kr.ac.chosun.mspace.climate.domain.Forecaster;
import kr.ac.chosun.mspace.climate.domain.Metric;
import kr.ac.chosun.mspace.climate.domain.MetricHistory;
import kr.ac.chosun.mspace.climate.domain.MetricRule;
import kr.ac.chosun.mspace.climate.domain.Sample;
import kr.ac.chosun.mspace.climate.domain.TrendForecaster;

/**
 * 예측 모델 비교 (재웅 11/13 "실습 시계열 모델 가져와서 써 보자", "실측으로 계속 올려"). 실습실 실측 1분 기록(센서 3개)을
 * 1분씩 흘리며 dev 설정(1000ppm 고정 경계, 30분 거름)으로 판마다 하나씩 바꿔 돌린다.
 * <ul>
 *   <li>확인용(기본): 녹화 10/21~11/8. 모델 학습·시험 어디에도 안 쓴 기간</li>
 *   <li>맞추기용({@code -Ddata=}): 실습 저장소 raw/influx에서 같은 센서 3개를 8/26~10/21로 꺼낸 것. 모델 시험 기간이라 학습엔 안 씀.
 *       7판부터는 이 기간 점수로만 고르고, 확인용 점수는 고른 뒤에 본다</li>
 * </ul>
 * 점수: 1000ppm 넘은 첫 분을 정답으로 미리 맞힘·늦음·놓침·헛알림, 그리고 5분마다 15분·30분 뒤 CO2 오차(평균 절대 오차).
 * {@code -Ddetail=1}이면 헛알림·늦음마다 앞뒤 30분 값을 찍는다.
 * 실행: {@code java -cp target/classes:target/test-classes:$(cat cp.txt) kr.ac.chosun.mspace.climate.eval.ForecastCompareCard [판 번호]}
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

    /** 한 판의 설정. 판마다 앞 판에서 하나만 바꾼다 */
    record Round(String name, Forecaster forecaster, int lead, int confirm, double margin, int truthRun) {

        Round(String name, Forecaster forecaster, int lead) {
            this(name, forecaster, lead, 1, 0, 1);
        }
    }

    public static void main(String[] args) throws IOException {
        Map<Integer, Map<Metric, TreeMap<Instant, Double>>> data;
        // -Ddata=파일: 다른 기간(예: 실습 저장소에서 꺼낸 8/26~10/21 맞추기용)으로 돌린다. 없으면 녹화(10/21~11/8, 확인용)
        String file = System.getProperty("data");
        try (InputStream in = file == null ? ForecastCompareCard.class.getResourceAsStream("/replay/lab-1min.csv.gz")
                : java.nio.file.Files.newInputStream(java.nio.file.Path.of(file))) {
            data = load(in);
        }
        TrendForecaster trend = new TrendForecaster(Duration.ofMinutes(30), 5);
        ClassLoader cl = ForecastCompareCard.class.getClassLoader();
        GruForecaster last = GruForecaster.load(cl, trend, SEOUL, 1);   // 3판까지: 마지막 1분 값에서 출발
        GruForecaster gru = GruForecaster.load(cl, trend, SEOUL, 3);    // 7판부터: 최근 3분 평균에서 출발
        if (last == null || gru == null) {
            throw new IllegalStateException("모델 파일 없음");
        }
        System.out.println("| 예측 | 넘음 | 미리 맞힘 | 늦음 | 놓침 | 미리 알림 | 헛알림 | 미리 맞힘률 | 미리 알림 정확도 | 균형 점수 | 몇 분 전(중앙) | 15분 뒤 CO2 오차 | 30분 뒤 CO2 오차 | 모델이 낸 비율 |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        Forecaster both = new Forecaster() {
            @Override
            public Forecast forecast(List<Sample> recent, Baseline b, Instant now) {
                return trend.forecast(recent, b, now);
            }

            @Override
            public Forecast forecast(Metric m, Map<Metric, List<Sample>> h, Baseline b, Instant now) {
                Forecast g = last.forecast(m, h, b, now);
                Forecast t = trend.forecast(m, h, b, now);
                return t.slopePerMinute() > 0 ? g : new Forecast(g.slopePerMinute(), null, null, g.points(), g.method());
            }
        };
        List<Round> rounds = List.of(
                new Round("0 직선(지금)", trend, 15),
                new Round("1 GRU", last, 15),
                new Round("2 GRU + 최근 30분 직선도 오를 때만", both, 15),
                new Round("3 GRU + 미리 볼 시간 15→10분", last, 10),
                new Round("4 GRU + 미리 볼 시간 15→20분", last, 20),
                new Round("5 (공정 비교) 직선 + 미리 볼 시간 15→10분", trend, 10),
                new Round("6 3판 + 출발점을 끝난 5분 칸 평균", GruForecaster.load(cl, trend, SEOUL, 0), 10),
                new Round("7 3판 + 출발점을 최근 3분 평균", gru, 10),
                new Round("8 3판 + 출발점을 최근 2분 평균", GruForecaster.load(cl, trend, SEOUL, 2), 10),
                new Round("9 3판 + 출발점을 최근 4분 평균", GruForecaster.load(cl, trend, SEOUL, 4), 10),
                new Round("10 3판 + 출발점을 최근 5분 평균", GruForecaster.load(cl, trend, SEOUL, 5), 10),
                new Round("11 7판 + 미리 볼 시간 10→12분", gru, 12),
                new Round("12 7판 + 두 번 연속 '곧 넘음'일 때만", gru, 10, 2, 0, 1),
                new Round("13 7판 + 세 번 연속 '곧 넘음'일 때만", gru, 10, 3, 0, 1),
                new Round("14 7판 + 예측 최고가 1005 넘을 때만", gru, 10, 1, 5, 1),
                new Round("15 7판 + 예측 최고가 1010 넘을 때만", gru, 10, 1, 10, 1),
                new Round("16 7판 + 직선 25% 섞기", new BlendForecaster(gru, trend, 0.25), 10),
                new Round("17 (참고) 16판, 정답을 3번 연속 넘음으로", new BlendForecaster(gru, trend, 0.25), 10, 1, 0, 3));
        for (Round r : rounds) {
            if (args.length > 0 && !r.name().startsWith(args[0] + " ")) {
                continue;
            }
            long t0 = System.nanoTime();
            Score s = score(data, r);
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

    /** 1000ppm 넘은 분. run>1이면 연달아 run번 이상 넘은 값들만 (그 사이 빈 분은 3분까지 이어 본다) */
    static java.util.NavigableSet<Instant> highMinutes(TreeMap<Instant, Double> co2, int run) {
        java.util.TreeSet<Instant> out = new java.util.TreeSet<>();
        List<Instant> cur = new ArrayList<>();
        Instant prev = null;
        for (var s : co2.entrySet()) {
            boolean joined = prev != null && !prev.plus(Duration.ofMinutes(3)).isBefore(s.getKey());
            if (s.getValue() >= LIMIT && (cur.isEmpty() || joined)) {
                cur.add(s.getKey());
            } else {
                if (cur.size() >= run) {
                    out.addAll(cur);
                }
                cur.clear();
                if (s.getValue() >= LIMIT) {
                    cur.add(s.getKey());
                }
            }
            prev = s.getKey();
        }
        if (cur.size() >= run) {
            out.addAll(cur);
        }
        return out;
    }

    static void detail(String what, Instant at, TreeMap<Instant, Double> co2) {
        if (System.getProperty("detail") == null) {
            return;
        }
        StringBuilder b = new StringBuilder();
        for (int m = -30; m <= 30; m += 5) {
            Double v = co2.get(at.plus(Duration.ofMinutes(m)));
            b.append(v == null ? "  - " : "%4.0f".formatted(v)).append(m == 0 ? "|" : " ");
        }
        System.out.printf("%s %s  [%s]%n", at.atZone(SEOUL).toLocalDateTime(), what, b);
    }

    static Score score(Map<Integer, Map<Metric, TreeMap<Instant, Double>>> data, Round round) {
        String name = round.name();
        Forecaster forecaster = round.forecaster();
        Duration lead = Duration.ofMinutes(round.lead());
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
            Map<Instant, String> why = new java.util.HashMap<>();
            int streak = 0;
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
                double margin = round.margin();
                if (margin > 0 && f.etaMinutes() != null && f.points().stream().mapToDouble(Sample::value).max().orElse(0) < LIMIT + margin) {
                    f = new Forecast(f.slopePerMinute(), null, null, f.points(), f.method());
                }
                judged++;
                gruShare += Forecast.GRU.equals(f.method()) ? 1 : 0;
                boolean high = false;
                boolean over = false;
                for (Finding x : detector.detect(Metric.CO2, c, b, f, rule, at)) {
                    high |= x.kind() == Finding.Kind.OVER || x.kind() == Finding.Kind.RISING_SOON;
                    over |= x.kind() == Finding.Kind.OVER;
                }
                int confirm = round.confirm();
                streak = high ? streak + 1 : 0;
                high = over || (high && streak >= confirm);
                if (high && (lastAlert == null || !lastAlert.plus(every).isAfter(now))) {
                    lastAlert = now;
                    sent.add(now);
                    if (latest.value() < LIMIT) {
                        pre.add(now);
                        why.put(now, "값 %.0f 기울기 %.1f eta %s %s".formatted(latest.value(), f.slopePerMinute(), f.etaMinutes(),
                                f.points().isEmpty() ? "" : "15분 %.0f".formatted(f.points().get(Math.min(2, f.points().size() - 1)).value())));
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
            java.util.NavigableSet<Instant> highs = highMinutes(co2, round.truthRun());
            List<Instant> cross = new ArrayList<>();
            Instant lastHigh = null;
            for (var s : co2.entrySet()) {
                if (highs.contains(s.getKey())) {
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
                    detail("늦음", x, co2);
                } else {
                    missed++;
                    detail("놓침", x, co2);
                }
            }
            alerts += pre.size();
            for (Instant a : pre) {
                if (highs.subSet(a, true, a.plus(Duration.ofMinutes(30)), true).isEmpty()) {
                    falseAlerts++;
                    detail("헛알림 " + why.get(a), a, co2);
                }
            }
        }
        leads.sort(Double::compare);
        return new Score(name, crossings, early, late, missed, alerts, falseAlerts,
                leads.isEmpty() ? 0 : leads.get(leads.size() / 2), n15 == 0 ? 0 : err15 / n15, n30 == 0 ? 0 : err30 / n30,
                gruShare, judged);
    }
}
