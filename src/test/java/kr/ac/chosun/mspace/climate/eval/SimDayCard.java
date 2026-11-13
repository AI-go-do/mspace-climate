package kr.ac.chosun.mspace.climate.eval;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import kr.ac.chosun.mspace.climate.MutableClock;
import kr.ac.chosun.mspace.climate.adapter.in.scheduler.SimFeeder;
import kr.ac.chosun.mspace.climate.appliance.ApplianceKind;
import kr.ac.chosun.mspace.climate.appliance.ClimateFacade;
import kr.ac.chosun.mspace.climate.appliance.VirtualAppliance;
import kr.ac.chosun.mspace.climate.application.Alert;
import kr.ac.chosun.mspace.climate.application.AlertLog;
import kr.ac.chosun.mspace.climate.application.ClimateAgent;
import kr.ac.chosun.mspace.climate.application.ClimateProperties;
import kr.ac.chosun.mspace.climate.application.ClimateStore;
import kr.ac.chosun.mspace.climate.application.Decision;
import kr.ac.chosun.mspace.climate.application.DecisionBook;
import kr.ac.chosun.mspace.climate.application.ReadingIntake;
import kr.ac.chosun.mspace.climate.application.RoomAnalyzer;
import kr.ac.chosun.mspace.climate.application.RoomSimulator;
import kr.ac.chosun.mspace.climate.application.SensorReading;
import kr.ac.chosun.mspace.climate.domain.AnomalyDetector;
import kr.ac.chosun.mspace.climate.domain.BaselineCalculator;
import kr.ac.chosun.mspace.climate.domain.Metric;
import kr.ac.chosun.mspace.climate.domain.MetricRule;
import kr.ac.chosun.mspace.climate.domain.TrendForecaster;

/**
 * 시뮬레이션 하루 채점 (재웅 11/13 "실제라면 이상탐지가 많이 나오면 그게 더 이상한 거지").
 * dev와 같은 설정(application.yml)으로 시뮬레이션 → 저장 → 분석 → 오디 판단을 1초씩 하루 돌리고, 판단 카드(이상)가 몇 번 열렸는지 센다.
 * 1번 방(실제 방)은 하루 두 번(13~15시, 18~20시) 예약이 있다고 친다. 기기는 가상 기기.
 * 실행: {@code java -cp target/classes:target/test-classes:$(cat cp.txt) kr.ac.chosun.mspace.climate.eval.SimDayCard [시간] [씨앗 수]}
 */
public final class SimDayCard {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static void main(String[] args) {
        int hours = args.length > 0 ? Integer.parseInt(args[0]) : 24;
        int seeds = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        double eventEvery = args.length > 2 ? Double.parseDouble(args[2]) : 240;
        Map<String, Integer> total = new TreeMap<>();
        int alertsTotal = 0;
        int cardsTotal = 0;
        for (int s = 1; s <= seeds; s++) {
            Result r = run(hours, s, eventEvery);
            System.out.printf("씨앗 %d: 카드 %d · 알림 %d · %s%n", s, r.cards, r.alerts, r.byKind);
            r.byKind.forEach((k, v) -> total.merge(k, v, Integer::sum));
            alertsTotal += r.alerts;
            cardsTotal += r.cards;
        }
        double per = 24.0 / hours / seeds;
        System.out.printf("하루 평균(%d시간 × %d번): 카드 %.1f · 알림 %.1f%n", hours, seeds, cardsTotal * per, alertsTotal * per);
        total.forEach((k, v) -> System.out.printf("  %s %.1f%n", k, v * per));
    }

    record Result(int cards, int alerts, Map<String, Integer> byKind) {
    }

    static Result run(int hours, long seed, double eventEvery) {
        Instant start = Instant.parse("2026-11-12T00:00:00Z"); // 09:00 KST
        MutableClock clock = new MutableClock(start);
        Map<Integer, String> rooms = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            rooms.put(i, i + "번 방");
        }
        ClimateProperties props = new ClimateProperties(rooms, Duration.ofDays(7), 2.5, 30, Duration.ofMinutes(30), 5,
                Duration.ofMinutes(10), Duration.ofMinutes(20), Duration.ofMinutes(10), Duration.ofMinutes(10),
                Duration.ofMinutes(30), Duration.ofMinutes(30),
                Map.of(Metric.CO2, new MetricRule(null, 1000.0, 150, 400, true, null),
                        Metric.TEMPERATURE, new MetricRule(18.0, 28.0, 1.5, 4, false, false),
                        Metric.HUMIDITY, new MetricRule(30.0, 70.0, 8, 20, false, false)),
                new ClimateProperties.Comfort(24, 26, 20, 22), List.of(), null, null, Duration.ofMinutes(5));
        ClimateStore store = new ClimateStore(props, clock);
        List<VirtualAppliance> devices = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            devices.add(new VirtualAppliance("ac-" + i, i, ApplianceKind.AIRCON));
            devices.add(new VirtualAppliance("purifier-" + i, i, ApplianceKind.PURIFIER));
            devices.add(new VirtualAppliance("fan-" + i, i, ApplianceKind.VENTILATOR));
        }
        ClimateFacade facade = new ClimateFacade(List.copyOf(devices), props.holdManual(), 500, clock);
        List<Alert> sent = new ArrayList<>();
        AlertLog alerts = new AlertLog(props.alertEvery(), 500, sent::add, clock);
        // dev와 같게: GRU 예측(최근 3분 평균 출발 + 직선 25%, 못 하면 직선) + 10분 전 알림 (README 예측 모델 비교 16판)
        TrendForecaster trend = new TrendForecaster(Duration.ofMinutes(30), 5);
        var gru = kr.ac.chosun.mspace.climate.adapter.out.model.GruForecaster.load(SimDayCard.class.getClassLoader(), trend, SEOUL);
        RoomAnalyzer analyzer = new RoomAnalyzer(props, new BaselineCalculator(SEOUL, 2.5, 30),
                gru == null ? trend : new kr.ac.chosun.mspace.climate.domain.BlendForecaster(gru, trend, 0.25),
                new AnomalyDetector(Duration.ofMinutes(10), Duration.ofMinutes(20)), clock);
        DecisionBook book = new DecisionBook(500);
        Set<String> seen = new HashSet<>();
        Map<String, Integer> byKind = new TreeMap<>();
        Instant[] judgedFrom = {null};
        book.listen(d -> {
            if (judgedFrom[0] != null && seen.add(d.id())) {
                byKind.merge(d.roomId() + "번 " + d.metric() + " " + d.kind(), 1, Integer::sum);
            }
        });
        ClimateAgent agent = new ClimateAgent(store, analyzer, facade, alerts, props, clock, book, false);
        ReadingIntake intake = new ReadingIntake(store, null, agent, (r, src) -> { }, clock);
        Map<Integer, Integer> people = new LinkedHashMap<>();
        Map<Integer, int[]> range = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            people.put(i, i == 5 ? 7 : 4);
            if (i != 1) {
                range.put(i, i == 5 ? new int[] {5, 8} : new int[] {3, 5});
            }
        }
        RoomSimulator sim = new RoomSimulator(people, eventEvery, SEOUL, seed,
                new RoomSimulator.Visitors(range, 20, 90, 50, 120, 9, 22));
        SimFeeder feeder = new SimFeeder(sim, intake, store, facade, List.copyOf(people.keySet()), 180, 10, clock);
        feeder.backfill();
        judgedFrom[0] = clock.instant();
        int alertsBefore = sent.size();
        int seconds = hours * 3600;
        for (int t = 0; t < seconds; t++) {
            clock.advance(Duration.ofSeconds(1));
            if (t % 60 == 0) {
                int h = clock.instant().atZone(SEOUL).getHour();
                boolean booked = (h >= 13 && h < 15) || (h >= 18 && h < 20);
                store.accept(new SensorReading("occ-1-" + t, 1, "OCCUPANCY", booked, null, null, null, clock.instant()));
            }
            feeder.tick();
        }
        int cards = byKind.values().stream().mapToInt(Integer::intValue).sum();
        return new Result(cards, sent.size() - alertsBefore, byKind);
    }
}
