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
 * - 실제 방(시험하는 1번 방)은 사람이 있는지를 시뮬레이션이 정하지 않는다. core 방 현황(실제 예약·입실)을 받아 쓴다.
 *   인원은 core가 안 알려 줘서 방 크기로 가정한다
 * - 가상 방(2~5번)은 시뮬레이션이 손님을 만든다(재웅 11/12 "1번방에서만 테스트하니까 2-5번은 시뮬레이션 해도 되겠다").
 *   쉬는 틈 → 입실(방 크기 안 인원) → 머문 뒤 퇴실을 되풀이해 관제 화면이 살아 있게 한다
 * - 사람이 있는 방은 CO2·온도·습도가 오르고, 비면 천천히 내려간다
 * - 오디가 켠 기기가 값에 실제로 반영된다: 에어컨 냉방은 목표 온도로, 제습은 습도를 낮추고, 환풍기는 CO2를 바깥 수준으로
 * - 가끔(평균 4시간에 한 번) 사람이 있는 방에 "상황"(햇빛·난방기로 온도 상승, 문 닫고 붐벼 CO2 상승)이 생긴다.
 *   오디가 탐지 → 예측 → 제어하는 장면을 만든다. 시연 때는 trigger로 바로 일으킨다
 * - 실제 방처럼 이상은 드물게(재웅 11/13 "실제라면 이상탐지가 많이 나오면 그게 더 이상한 거지"): 보통 인원이면
 *   CO2는 1000ppm 밑(4명 약 860ppm), 온도는 1°C 안팎만 오른다. 가상 손님은 문 여는 시간(9~22시)에만 온다.
 *   큰 방(5번)은 공간이 커서 같은 인원에 덜 오른다
 * 숫자는 가정이다. 화면·API에는 source=SIM으로 밝힌다. 하루 이상 건수는 eval/SimDayCard로 잰다.
 */
public class RoomSimulator {

    /** 기기 상태 중 시뮬레이션에 쓰는 것 */
    public record Devices(boolean cooling, boolean heating, boolean drying, Integer targetC, boolean ventilating) {
        public static final Devices NONE = new Devices(false, false, false, null, false);
    }

    /** 방 하나의 지금 값. 실제 방의 occupied는 받은 재실(core) 그대로, people·until은 가상 방만(실제 방은 null) */
    public record State(int roomId, double co2, double temperatureC, double humidityPct, boolean occupied, String event,
                        Integer people, Instant until) {
    }

    /**
     * 가상 방 손님 만들기. people: 방 번호 → {최소, 최대} 인원. 이 맵에 있는 방만 가상 방이다.
     * 빈 틈 gapMin~gapMax분 뒤 들어와 stayMin~stayMax분 머문다(10분 단위). openHour~closeHour시에만 온다(같으면 하루 종일)
     */
    public record Visitors(Map<Integer, int[]> people, int gapMin, int gapMax, int stayMin, int stayMax,
                           int openHour, int closeHour) {
        public static final Visitors NONE = new Visitors(Map.of(), 0, 0, 0, 0, 0, 0);

        public Visitors(Map<Integer, int[]> people, int gapMin, int gapMax, int stayMin, int stayMax) {
            this(people, gapMin, gapMax, stayMin, stayMax, 0, 0);
        }

        boolean allDay() {
            return openHour == closeHour;
        }
    }

    /** 일부러 일으키는 상황(시연). HEAT: 온도 상승, STUFFY: 문 닫고 붐벼 CO2 상승 */
    public enum Event { HEAT, STUFFY }

    static final double WANDER_KEEP = 0.95;   // 1초에 5%씩 0으로(약 20초 주기)
    static final double WANDER_STEP = Math.sqrt(1 - WANDER_KEEP * WANDER_KEEP);
    static final double WANDER_CO2 = 8;
    static final double WANDER_TEMP = 0.05;
    static final double WANDER_HUM = 0.12;
    static final double OUTDOOR_CO2 = 520;   // 건물 안 빈 방 수준(실습실 새벽 550 안팎)
    static final double CO2_PER_PERSON = 3.0;     // ppm/분
    static final double CO2_LEAK = 0.035;         // /분 (문틈·공조로 빠지는 속도. 4명 약 860·5명 약 950ppm에서 멈춤)
    static final double CO2_VENT = 0.1;           // /분 (환풍기)
    static final double HEAT_PER_PERSON = 0.008;  // °C/분
    static final double TEMP_PULL = 0.03;         // /분 (건물 온도로 돌아가는 속도)
    static final double AIRCON_PULL = 0.07;       // /분 (에어컨이 목표 온도로 끄는 속도)
    static final double HUM_PER_PERSON = 0.02;    // %/분
    static final double HUM_PULL = 0.03;          // /분
    static final double DRY_PULL = 0.06;          // /분 (제습이 40%로 끄는 속도)
    static final double HEAT_EVENT = 0.12;        // °C/분 (상황: 햇빛·난방기)
    static final double STUFFY_EVENT = 30;        // ppm/분 (상황: 문 닫고 붐빔. 20분 안에 1000ppm을 넘김)
    static final double EVENT_MINUTES = 20;
    /** 이만큼 사람을 가정한 방을 크기 1로 본다. 5번 방(7명 가정)은 1.75배 커서 같은 인원에 덜 오른다 */
    static final double ROOM_UNIT = 4;

    private final ZoneId zone;
    private final Random random;
    private final Map<Integer, Room> rooms = new LinkedHashMap<>();
    private final Map<Integer, Integer> assumed;
    private final double eventEveryMinutes;
    private final Visitors visitors;
    private double nextEventIn;

    /**
     * @param assumed           방 번호 → 사람이 있을 때 가정하는 인원(공기 값 계산에만 씀)
     * @param eventEveryMinutes 평균 몇 분마다 사람이 있는 방에 상황(온도 상승)을 만들지
     */
    private final java.util.Set<Integer> external = new java.util.HashSet<>();

    public RoomSimulator(Map<Integer, Integer> assumed, double eventEveryMinutes, ZoneId zone, long seed) {
        this(assumed, eventEveryMinutes, zone, seed, Visitors.NONE);
    }

    /** visitors에 있는 방은 시뮬레이션이 손님(입실·인원·퇴실)을 만든다 */
    public RoomSimulator(Map<Integer, Integer> assumed, double eventEveryMinutes, ZoneId zone, long seed, Visitors visitors) {
        this.assumed = assumed;
        this.visitors = visitors == null ? Visitors.NONE : visitors;
        this.eventEveryMinutes = eventEveryMinutes;
        this.zone = zone;
        this.random = new Random(seed);
        this.nextEventIn = eventEveryMinutes * 0.3;
        assumed.keySet().stream().sorted().forEach(id -> rooms.put(id, new Room(id)));
    }

    /** 시작 값: 빈 방, 실습실 밤 수준 */
    public void start(Instant now) {
        for (Room r : rooms.values()) {
            r.co2 = 560 + random.nextGaussian() * 15;
            r.temp = ambient(r.id, now);
            r.hum = 47.5 + random.nextGaussian();
            if (external.contains(r.id)) {
                r.occupied = false;
                r.nextAt = null;
            } else if (isVirtual(r.id)) {
                // 시작할 때 절반쯤은 이미 쓰는 중으로(관제 화면이 처음부터 비어 보이지 않게)
                if (minutesToClose(now) >= 30 && random.nextBoolean()) {
                    checkIn(r, now.minusSeconds(60L * random.nextInt(Math.max(1, visitors.stayMin()))));
                } else {
                    r.occupied = false;
                    r.nextAt = minutesToClose(now) >= 30 ? now.plusSeconds(60L * between(0, visitors.gapMax()))
                            : firstGuest(now);
                }
            }
        }
    }

    /** 이 방은 시뮬레이션이 손님을 만드는 가상 방인지 */
    public boolean isVirtual(int roomId) {
        return visitors.people().containsKey(roomId);
    }

    /**
     * 가상 방 손님을 바깥(가상 학생: core 실제 예약·QR 입실)이 정하게 한다(재웅 11/14 "사용자 화면도 실시간으로").
     * 이 방들은 스스로 손님을 만들지 않고 {@link #enter}·{@link #leave}로만 바뀐다. 재실 신호(core로 보냄)는 그대로 낸다
     */
    public synchronized void useExternalGuests(java.util.Set<Integer> roomIds) {
        external.clear();
        roomIds.stream().filter(this::isVirtual).forEach(external::add);
        for (int id : external) {
            Room r = rooms.get(id);
            if (r != null && r.occupied) {
                r.occupied = false;
                r.people = null;
                r.nextAt = null;
            }
        }
    }

    /** 바깥이 손님을 정하는 방인지 */
    public boolean isExternal(int roomId) {
        return external.contains(roomId);
    }

    /** 바깥 손님 입실(또는 인원·나갈 시각 고침). until은 화면에 보이는 나갈 예정 시각 */
    public synchronized void enter(int roomId, int people, Instant until) {
        Room r = rooms.get(roomId);
        if (r == null || !external.contains(roomId)) {
            return;
        }
        r.occupied = true;
        r.people = people;
        r.nextAt = until;
    }

    /** 바깥 손님 퇴실 */
    public synchronized void leave(int roomId) {
        Room r = rooms.get(roomId);
        if (r == null || !external.contains(roomId)) {
            return;
        }
        r.occupied = false;
        r.people = null;
        r.nextAt = null;
        r.event = null;
        r.eventLeft = 0;
    }

    /** 지금 사람이 있는지(시뮬레이션이 아는 값) */
    public synchronized boolean occupied(int roomId) {
        Room r = rooms.get(roomId);
        return r != null && r.occupied;
    }

    /**
     * dtMinutes만큼 흐르게 한다. devices: 방 번호 → 지금 켜진 기기, occupied: 방 번호 → 지금 사람이 있는지(core 방 현황).
     * occupied에 없거나 모르면(null) 빈 방으로 친다
     */
    public synchronized void step(Instant now, double dtMinutes, Map<Integer, Devices> devices,
                                  Map<Integer, Boolean> occupied) {
        nextEventIn -= dtMinutes;
        if (nextEventIn <= 0) {
            startEvent();
            nextEventIn = eventEveryMinutes * (0.7 + random.nextDouble() * 0.6);
        }
        for (Room r : rooms.values()) {
            if (isVirtual(r.id)) {
                visit(r, now);
            } else {
                r.occupied = Boolean.TRUE.equals(occupied.get(r.id));
            }
            Devices d = devices.getOrDefault(r.id, Devices.NONE);
            int people = !r.occupied ? 0 : r.people != null ? r.people : assumed.getOrDefault(r.id, 4);
            double n = people / size(r.id);
            double vent = CO2_LEAK + (d.ventilating() ? CO2_VENT : 0);
            double stuffy = r.eventLeft > 0 && Event.STUFFY.name().equals(r.event) ? STUFFY_EVENT : 0;
            r.co2 += (n * CO2_PER_PERSON + stuffy - vent * (r.co2 - OUTDOOR_CO2)) * dtMinutes;

            double heat = n * HEAT_PER_PERSON + (r.eventLeft > 0 && Event.HEAT.name().equals(r.event) ? HEAT_EVENT : 0);
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

    /**
     * 지금 값. 실제 센서처럼 두 가지 흔들림을 더한다(재웅 11/12 "몇 초마다 움직이는 걸 보고 싶다").
     * 천천히 오르내리는 흔들림(약 20초 주기, 공기 흐름) + 매번 다른 잔떨림(측정 오차).
     * 크기는 실측(lab-1min, 새벽 조용한 때 1분 차이 CO2 2~16ppm·온도 0.04~0.08℃·습도 0.14~0.21%)에 맞춤
     */
    public synchronized List<State> read() {
        List<State> out = new ArrayList<>();
        for (Room r : rooms.values()) {
            r.wCo2 = wander(r.wCo2, WANDER_CO2);
            r.wTemp = wander(r.wTemp, WANDER_TEMP);
            r.wHum = wander(r.wHum, WANDER_HUM);
            out.add(new State(r.id, Math.max(OUTDOOR_CO2 - 20, r.co2 + r.wCo2 + random.nextGaussian() * 4),
                    r.temp + r.wTemp + random.nextGaussian() * 0.02, clamp(r.hum + r.wHum + random.nextGaussian() * 0.08, 5, 95),
                    r.occupied, r.event, r.occupied ? r.people : null, r.occupied ? r.nextAt : null));
        }
        return out;
    }

    /** 한 번 읽을 때(1초)마다 조금씩 0 쪽으로 돌아가며 떠도는 값. 오래 보면 표준편차가 sd */
    private double wander(double w, double sd) {
        return w * WANDER_KEEP + random.nextGaussian() * sd * WANDER_STEP;
    }

    /** 가상 방: 때가 되면 들어오고 나간다. 문 닫는 시간에는 안 온다 */
    private void visit(Room r, Instant now) {
        if (external.contains(r.id) || r.nextAt == null || now.isBefore(r.nextAt)) {
            return;
        }
        if (r.occupied) {
            r.occupied = false;
            r.people = null;
            r.event = null;
            r.eventLeft = 0;
            r.nextAt = now.plusSeconds(60L * between(visitors.gapMin(), visitors.gapMax()));
        } else if (minutesToClose(now) < 30) {
            r.nextAt = firstGuest(now);
        } else {
            checkIn(r, now);
        }
    }

    private void checkIn(Room r, Instant at) {
        int[] p = visitors.people().get(r.id);
        r.occupied = true;
        r.people = between(p[0], p[1]);
        int stay = Math.round(between(visitors.stayMin(), visitors.stayMax()) / 10f) * 10;
        r.nextAt = at.plusSeconds(60L * Math.max(10, (int) Math.min(stay, minutesToClose(at))));
    }

    /** 문 닫을 때까지 남은 분(문 닫은 동안은 0, 하루 종일 열면 아주 큼) */
    private long minutesToClose(Instant at) {
        if (visitors.allDay()) {
            return Long.MAX_VALUE / 120;
        }
        var t = at.atZone(zone);
        if (t.getHour() < visitors.openHour() || t.getHour() >= visitors.closeHour()) {
            return 0;
        }
        return (visitors.closeHour() - t.getHour()) * 60L - t.getMinute();
    }

    /** 문 연 뒤 첫 손님: 여는 시각부터 빈 틈 최소(20분) 안에. 아침에 방이 1시간 넘게 비어 보이지 않게(재웅 11/13 9:18 "다 비었다") */
    private Instant firstGuest(Instant now) {
        return nextOpen(now).plusSeconds(60L * between(0, visitors.gapMin()));
    }

    /** 다음 문 여는 시각 */
    private Instant nextOpen(Instant at) {
        var t = at.atZone(zone).withMinute(0).withSecond(0).withNano(0);
        var open = t.withHour(visitors.openHour());
        if (!open.isAfter(at.atZone(zone))) {
            open = open.plusDays(1);
        }
        return open.toInstant();
    }

    /** 방 크기(작은 방 = 1). 클수록 같은 인원에 공기 값이 덜 바뀐다 */
    private double size(int roomId) {
        return Math.max(1.0, assumed.getOrDefault(roomId, (int) ROOM_UNIT) / ROOM_UNIT);
    }

    private int between(int lo, int hi) {
        return hi <= lo ? lo : lo + random.nextInt(hi - lo + 1);
    }

    /** 사람이 있는 방 하나에 상황(온도 또는 CO2 상승)을 만든다(빈 방이면 오디가 기기를 안 켜서). 아무도 없으면 건너뛴다 */
    private void startEvent() {
        List<Room> busy = rooms.values().stream().filter(r -> r.occupied && r.eventLeft <= 0).toList();
        if (busy.isEmpty()) {
            return;
        }
        Room r = busy.get(random.nextInt(busy.size()));
        r.event = (random.nextBoolean() ? Event.HEAT : Event.STUFFY).name();
        r.eventLeft = EVENT_MINUTES;
    }

    /**
     * 시연용: 이 방에 지금 상황을 일으킨다(재웅 11/13 "필요할 때 일부러"). 가상 방이 비어 있으면 손님을 먼저 들인다.
     * 실제 방은 재실을 core가 정하니 사람이 없으면 값만 오르고 오디는 기기를 켜지 않는다. 없는 방이면 false
     */
    public synchronized boolean trigger(int roomId, Event kind, Instant now) {
        Room r = rooms.get(roomId);
        if (r == null) {
            return false;
        }
        if (isVirtual(roomId) && !external.contains(roomId) && !r.occupied) {
            checkIn(r, now);
            r.nextAt = Instant.ofEpochMilli(Math.max(r.nextAt.toEpochMilli(),
                    now.plusSeconds(60L * (long) (EVENT_MINUTES + 30)).toEpochMilli()));
        }
        r.event = kind.name();
        r.eventLeft = EVENT_MINUTES;
        return true;
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
        boolean occupied;
        Integer people;       // 가상 방 손님 수
        Instant nextAt;       // 가상 방: 다음 입실(빈 방) 또는 퇴실(사용 중) 시각
        String event;
        double eventLeft;
        double wCo2;          // 센서 흔들림(천천히 떠도는 몫)
        double wTemp;
        double wHum;

        Room(int id) {
            this.id = id;
        }
    }
}
