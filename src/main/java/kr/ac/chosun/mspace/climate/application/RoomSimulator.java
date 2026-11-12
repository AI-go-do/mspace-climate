package kr.ac.chosun.mspace.climate.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 방 공기 시뮬레이션 (FR-DEV-107, 재웅 11/12 "가짜 값으로 시뮬레이션 돌려서 에어컨 자동 제어를 보여 주자").
 * 실습실 센서가 멈춘 동안 방 5개 값을 1초마다 만든다. 수준은 실습실 실측(10/21~11/8: CO2 빈 방 560·수업 900~1300ppm,
 * 온도 21~22°C, 습도 47%)에 맞췄다.
 * - 사람이 들어오면 CO2·온도·습도가 오르고, 나가면 천천히 내려간다
 * - 오디가 켠 기기가 값에 실제로 반영된다: 에어컨 냉방은 목표 온도로, 제습은 습도를 낮추고, 환풍기는 CO2를 바깥 수준으로
 * - 가끔 한 방에 "상황"이 생긴다(햇빛·난방기로 온도 상승, 사람이 몰림). 오디가 탐지 → 예측 → 제어하는 장면을 만든다
 * 숫자는 가정이다. 화면·API에는 source=SIM으로 밝힌다.
 */
public class RoomSimulator {

    /** 기기 상태 중 시뮬레이션에 쓰는 것 */
    public record Devices(boolean cooling, boolean heating, boolean drying, Integer targetC, boolean ventilating) {
        public static final Devices NONE = new Devices(false, false, false, null, false);
    }

    /** 방 하나의 지금 값. people은 몰려온 사람(CROWD)까지 센 인원 */
    public record State(int roomId, double co2, double temperatureC, double humidityPct, boolean occupied, int people,
                        String event) {
    }

    static final double OUTDOOR_CO2 = 520;   // 건물 안 빈 방 수준(실습실 새벽 550 안팎)
    static final double CO2_PER_PERSON = 3.0;     // ppm/분
    static final double CO2_LEAK = 0.02;          // /분 (문틈·공조로 빠지는 속도. 4명이면 1000ppm 안팎에서 멈춤)
    static final double CO2_VENT = 0.1;           // /분 (환풍기)
    static final double HEAT_PER_PERSON = 0.008;  // °C/분
    static final double TEMP_PULL = 0.03;         // /분 (건물 온도로 돌아가는 속도)
    static final double AIRCON_PULL = 0.07;       // /분 (에어컨이 목표 온도로 끄는 속도)
    static final double HUM_PER_PERSON = 0.02;    // %/분
    static final double HUM_PULL = 0.03;          // /분
    static final double DRY_PULL = 0.06;          // /분 (제습이 40%로 끄는 속도)

    private final ZoneId zone;
    private final Random random;
    private final Map<Integer, Room> rooms = new LinkedHashMap<>();
    private final Map<Integer, Integer> capacity;
    private final double eventEveryMinutes;
    private double nextEventIn;

    /**
     * @param capacity          방 번호 → 최대 인원
     * @param eventEveryMinutes 평균 몇 분마다 한 방에 상황(온도 상승·사람 몰림)을 만들지
     */
    public RoomSimulator(Map<Integer, Integer> capacity, double eventEveryMinutes, ZoneId zone, long seed) {
        this.capacity = capacity;
        this.eventEveryMinutes = eventEveryMinutes;
        this.zone = zone;
        this.random = new Random(seed);
        this.nextEventIn = eventEveryMinutes * 0.3;
        capacity.keySet().stream().sorted().forEach(id -> rooms.put(id, new Room(id)));
    }

    /** 시작 값: 빈 방, 실습실 밤 수준 */
    public void start(Instant now) {
        for (Room r : rooms.values()) {
            r.co2 = 560 + random.nextGaussian() * 15;
            r.temp = ambient(r.id, now);
            r.hum = 47.5 + random.nextGaussian();
            r.gapLeft = 2 + random.nextDouble() * 12;
        }
    }

    /** dtMinutes만큼 흐르게 한다. devices: 방 번호 → 지금 켜진 기기 */
    public synchronized void step(Instant now, double dtMinutes, Map<Integer, Devices> devices) {
        nextEventIn -= dtMinutes;
        if (nextEventIn <= 0) {
            startEvent();
            nextEventIn = eventEveryMinutes * (0.7 + random.nextDouble() * 0.6);
        }
        int hour = now.atZone(zone).getHour();
        for (Room r : rooms.values()) {
            occupancy(r, hour, dtMinutes);
            Devices d = devices.getOrDefault(r.id, Devices.NONE);
            int n = r.people + (r.eventLeft > 0 && "CROWD".equals(r.event) ? 4 : 0);
            double vent = CO2_LEAK + (d.ventilating() ? CO2_VENT : 0);
            r.co2 += (n * CO2_PER_PERSON - vent * (r.co2 - OUTDOOR_CO2)) * dtMinutes;

            double heat = n * HEAT_PER_PERSON + (r.eventLeft > 0 && "HEAT".equals(r.event) ? 0.12 : 0);
            double pull = TEMP_PULL * (ambient(r.id, now) - r.temp);
            if ((d.cooling() && d.targetC() != null && r.temp > d.targetC())
                    || (d.heating() && d.targetC() != null && r.temp < d.targetC())) {
                pull += AIRCON_PULL * (d.targetC() - r.temp);
            }
            r.temp += (heat + pull) * dtMinutes;

            double hum = n * HUM_PER_PERSON + HUM_PULL * (47.5 - r.hum) + (d.drying() ? DRY_PULL * (40 - r.hum) : 0)
                    + (d.cooling() ? DRY_PULL * 0.3 * (42 - r.hum) : 0);
            r.hum += hum * dtMinutes;

            if (r.eventLeft > 0) {
                r.eventLeft -= dtMinutes;
                if (r.eventLeft <= 0) {
                    r.event = null;
                }
            }
        }
    }

    /** 지금 값(센서가 재는 것처럼 작은 흔들림을 더함) */
    public synchronized List<State> read() {
        List<State> out = new ArrayList<>();
        for (Room r : rooms.values()) {
            int crowd = r.eventLeft > 0 && "CROWD".equals(r.event) ? 4 : 0;
            out.add(new State(r.id, Math.max(OUTDOOR_CO2 - 20, r.co2 + random.nextGaussian() * 4),
                    r.temp + random.nextGaussian() * 0.03, clamp(r.hum + random.nextGaussian() * 0.2, 5, 95),
                    r.people + crowd > 0, r.people + crowd, r.event));
        }
        return out;
    }

    /** 사람이 들고 나는 것: 낮에는 15분 쉬고 30~90분 쓰는 흐름, 밤에는 드물게 */
    private void occupancy(Room r, int hour, double dt) {
        if (r.people > 0) {
            r.stayLeft -= dt;
            if (r.stayLeft <= 0) {
                r.people = 0;
                r.gapLeft = (hour >= 9 && hour < 23 ? 8 : 60) + random.nextDouble() * 20;
            }
            return;
        }
        r.gapLeft -= dt;
        if (r.gapLeft <= 0) {
            int max = capacity.getOrDefault(r.id, 5);
            r.people = 2 + random.nextInt(Math.max(1, max - 1));
            r.stayLeft = 30 + random.nextDouble() * 60;
        }
    }

    /** 한 방에 상황을 만든다. 사람이 있는 방을 고른다(빈 방이면 오디가 기기를 안 켜서) */
    private void startEvent() {
        List<Room> busy = rooms.values().stream().filter(r -> r.people > 0 && r.eventLeft <= 0).toList();
        if (busy.isEmpty()) {
            return;
        }
        Room r = busy.get(random.nextInt(busy.size()));
        r.event = random.nextBoolean() ? "HEAT" : "CROWD";
        r.eventLeft = 20;
        r.stayLeft = Math.max(r.stayLeft, 35);
    }

    /** 방마다 조금 다른 건물 온도. 오후 5시쯤 가장 높고 새벽에 낮다(실습실 21.0~22.1°C) */
    private double ambient(int roomId, Instant now) {
        var t = now.atZone(zone);
        double h = t.getHour() + t.getMinute() / 60.0;
        return 21.5 + 0.55 * Math.sin((h - 11) / 24 * 2 * Math.PI) + (roomId - 3) * 0.2;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static final class Room {
        final int id;
        double co2;
        double temp;
        double hum;
        int people;
        double stayLeft;
        double gapLeft;
        String event;
        double eventLeft;

        Room(int id) {
            this.id = id;
        }
    }
}
