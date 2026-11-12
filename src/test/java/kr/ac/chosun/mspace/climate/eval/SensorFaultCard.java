package kr.ac.chosun.mspace.climate.eval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import kr.ac.chosun.mspace.climate.domain.AnomalyDetector;
import kr.ac.chosun.mspace.climate.domain.Baseline;
import kr.ac.chosun.mspace.climate.domain.Finding;
import kr.ac.chosun.mspace.climate.domain.Forecast;
import kr.ac.chosun.mspace.climate.domain.Metric;
import kr.ac.chosun.mspace.climate.domain.MetricRule;
import kr.ac.chosun.mspace.climate.domain.Sample;

/**
 * 센서 고장 채점표. 실측 19일(lab-1min.csv.gz, 방 1~3)로 두 가지를 잰다.
 * <ol>
 *   <li>헛알림: 실측 그대로 돌려 "센서 멈춤(STUCK)"·"튐(SPIKE)"이 몇 번 나는지. 실측은 고장 표시가 없어 난 알림은 모두 헛알림으로 센다
 *       (같은 방·같은 값·같은 종류는 30분에 한 번, 실제 알림과 같게)</li>
 *   <li>잡아냄: 실측 위에 일부러 고장을 넣고(값 40분 멈춤, 1분 튐) 잡는지. 방·값마다 같은 씨앗으로 30번씩</li>
 * </ol>
 * 실행: AirScoreCard와 같이 test-classes를 classpath에 두고 {@code SensorFaultCard [판]}
 */
public final class SensorFaultCard {

    static final Map<String, Integer> SPOTS = AirScoreCard.SPOTS;
    static final Duration ALERT_EVERY = Duration.ofMinutes(30);
    static final int INJECT = 30;
    static final int FREEZE_MIN = 40;

    /** 판: 멈춤 판단 시간과 값별 튐 기준 */
    public record Variant(String name, String what, Duration stuck, Map<Metric, Double> spike, Set<Metric> stuckOn) {
    }

    record Count(int realStuck, int realSpike, int caughtStuck, int caughtSpike, List<Integer> stuckDelay) {
    }

    public static void main(String[] args) throws IOException {
        Map<Metric, Map<Integer, TreeMap<Instant, Double>>> data;
        try (InputStream in = SensorFaultCard.class.getResourceAsStream("/replay/lab-1min.csv.gz")) {
            data = load(in);
        }
        long days = 0;
        for (var s : data.get(Metric.CO2).values()) {
            days += Duration.between(s.firstKey(), s.lastKey()).toDays();
        }
        System.out.printf("방 3개, 방-일 %d%n%n", days);
        System.out.println("| 판 | 바꾼 것 | 값 | 실측 멈춤 헛알림 | 실측 튐 헛알림 | 넣은 멈춤 잡음 | 멈춤 잡기까지(중앙) | 넣은 튐 잡음 |");
        System.out.println("|---|---|---|---|---|---|---|---|");
        for (Variant v : rounds()) {
            if (args.length > 0 && !v.name().equals(args[0])) {
                continue;
            }
            for (Metric m : Metric.values()) {
                Count c = score(data.get(m), m, v);
                List<Integer> d = new ArrayList<>(c.stuckDelay());
                d.sort(null);
                System.out.printf("| %s | %s | %s | %d | %d | %d/%d | %s | %d/%d |%n", v.name(), v.what(), AnomalyDetector.label(m),
                        c.realStuck(), c.realSpike(), c.caughtStuck(), INJECT * 3,
                        d.isEmpty() ? "–" : d.get(d.size() / 2) + "분", c.caughtSpike(), INJECT * 3);
            }
        }
    }

    static List<Variant> rounds() {
        Map<Metric, Double> spike = new EnumMap<>(Metric.class);
        spike.put(Metric.CO2, 400.0);
        spike.put(Metric.TEMPERATURE, 4.0);
        spike.put(Metric.HUMIDITY, 20.0);
        List<Variant> out = new ArrayList<>();
        Set<Metric> all = EnumSet.allOf(Metric.class);
        out.add(new Variant("0", "기준(멈춤 20분, 세 값 모두)", Duration.ofMinutes(20), spike, all));
        out.add(new Variant("1", "멈춤 20→30분", Duration.ofMinutes(30), spike, all));
        out.add(new Variant("2", "0 + 멈춤은 CO2로만", Duration.ofMinutes(20), spike, EnumSet.of(Metric.CO2)));
        return out;
    }

    static Count score(Map<Integer, TreeMap<Instant, Double>> rooms, Metric m, Variant v) {
        MetricRule rule = new MetricRule(null, null, 0, v.spike().get(m), false, v.stuckOn().contains(m));
        AnomalyDetector detector = new AnomalyDetector(Duration.ofMinutes(15), v.stuck());
        int realStuck = 0;
        int realSpike = 0;
        int caughtStuck = 0;
        int caughtSpike = 0;
        List<Integer> delay = new ArrayList<>();
        for (var e : rooms.entrySet()) {
            TreeMap<Instant, Double> series = e.getValue();
            int[] real = run(series, m, rule, detector, null, null);
            realStuck += real[0];
            realSpike += real[1];
            Random rnd = new Random(1112L * 31 + e.getKey() * 7 + m.ordinal());
            List<Instant> keys = new ArrayList<>(series.keySet());
            for (int i = 0; i < INJECT; i++) {
                // 멈춤: 고른 시각부터 40분 동안 마지막 값 그대로(센서가 같은 값을 계속 보냄)
                Instant at = keys.get(60 + rnd.nextInt(keys.size() - 200));
                TreeMap<Instant, Double> frozen = new TreeMap<>(series);
                double hold = series.get(at);
                Instant end = at.plus(Duration.ofMinutes(FREEZE_MIN));
                for (Instant t : series.subMap(at, true, end, false).keySet()) {
                    frozen.put(t, hold);
                }
                int[] r = run(frozen, m, rule, detector, at, end);
                if (r[2] >= 0) {
                    caughtStuck++;
                    delay.add(r[2]);
                }
                // 튐: 고른 1분 값만 크게(기준의 1.5배) 틀림
                Instant sp = keys.get(60 + rnd.nextInt(keys.size() - 200));
                TreeMap<Instant, Double> spiked = new TreeMap<>(series);
                spiked.put(sp, series.get(sp) + v.spike().get(m) * 1.5);
                int[] s = run(spiked, m, rule, detector, sp, sp.plus(Duration.ofMinutes(2)));
                if (s[3] >= 0) {
                    caughtSpike++;
                }
            }
        }
        return new Count(realStuck, realSpike, caughtStuck, caughtSpike, delay);
    }

    /**
     * 1분씩 흘리며 탐지. [실측 멈춤 알림 수, 실측 튐 알림 수, 넣은 구간 안 첫 멈춤까지 분(-1=못 잡음), 넣은 구간 안 튐(-1=못 잡음)].
     * from·to가 있으면 그 구간만 돌린다(앞 3시간 기록은 같이 넣는다)
     */
    static int[] run(TreeMap<Instant, Double> series, Metric m, MetricRule rule, AnomalyDetector detector, Instant from,
                     Instant to) {
        Baseline none = new Baseline(null, null, null, null, 0, true, "NONE");
        Forecast fc = Forecast.none();
        Instant start = from == null ? series.firstKey() : from.minus(Duration.ofHours(3));
        Instant stop = to == null ? series.lastKey() : to.plus(Duration.ofMinutes(5));
        List<Sample> recent = new ArrayList<>();
        Instant lastStuck = null;
        Instant lastSpike = null;
        int stuck = 0;
        int spike = 0;
        int firstStuck = -1;
        int firstSpike = -1;
        for (var e : series.subMap(start, true, stop, true).entrySet()) {
            Instant now = e.getKey();
            recent.add(new Sample(now, e.getValue()));
            if (recent.size() > 90) {
                recent.remove(0);
            }
            for (Finding f : detector.detect(m, recent, none, fc, rule, now)) {
                if (f.kind() == Finding.Kind.STUCK) {
                    if (from != null && !now.isBefore(from) && !now.isAfter(to.plus(Duration.ofMinutes(5))) && firstStuck < 0) {
                        firstStuck = (int) Duration.between(from, now).toMinutes();
                    }
                    if (lastStuck == null || !now.isBefore(lastStuck.plus(ALERT_EVERY))) {
                        stuck++;
                        lastStuck = now;
                    }
                } else if (f.kind() == Finding.Kind.SPIKE) {
                    if (from != null && !now.isBefore(from) && !now.isAfter(to) && firstSpike < 0) {
                        firstSpike = 0;
                    }
                    if (lastSpike == null || !now.isBefore(lastSpike.plus(ALERT_EVERY))) {
                        spike++;
                        lastSpike = now;
                    }
                }
            }
        }
        return new int[] {stuck, spike, firstStuck, firstSpike};
    }

    /** gzip CSV(minute_utc,spot,co2,temperature,humidity) → 값 종류 → 방 → 시각 → 값 */
    static Map<Metric, Map<Integer, TreeMap<Instant, Double>>> load(InputStream gz) throws IOException {
        Map<Metric, Map<Integer, TreeMap<Instant, Double>>> by = new EnumMap<>(Metric.class);
        for (Metric m : Metric.values()) {
            by.put(m, new LinkedHashMap<>());
        }
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(gz), StandardCharsets.UTF_8))) {
            String line = in.readLine();
            while ((line = in.readLine()) != null) {
                String[] f = line.split(",", -1);
                Integer room = f.length < 5 ? null : SPOTS.get(f[1]);
                if (room == null) {
                    continue;
                }
                Instant at = LocalDateTime.parse(f[0]).toInstant(ZoneOffset.UTC);
                put(by, Metric.CO2, room, at, f[2]);
                put(by, Metric.TEMPERATURE, room, at, f[3]);
                put(by, Metric.HUMIDITY, room, at, f[4]);
            }
        }
        return by;
    }

    private static void put(Map<Metric, Map<Integer, TreeMap<Instant, Double>>> by, Metric m, int room, Instant at, String v) {
        if (!v.isBlank()) {
            by.get(m).computeIfAbsent(room, k -> new TreeMap<>()).put(at, Double.parseDouble(v));
        }
    }

    private SensorFaultCard() {
    }
}
