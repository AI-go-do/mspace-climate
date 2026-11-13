package kr.ac.chosun.mspace.climate.application;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import kr.ac.chosun.mspace.climate.application.StudentDesk.Booked;
import kr.ac.chosun.mspace.climate.application.StudentDesk.Refused;
import kr.ac.chosun.mspace.climate.application.StudentDesk.RoomNow;
import kr.ac.chosun.mspace.climate.application.StudentDesk.Student;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 가상 학생 시뮬레이션 (재웅 11/14 "사용자 화면에서도 실시간성이 보이게"). 가상 방(2~5번)에 가상 학생들이
 * <b>실제 core 예약</b>을 만들고, 시간이 되면 문 앞 QR로 입실하고, 퇴실·연장·취소·노쇼를 한다.
 * 그래서 학생 앱의 방 목록·빈 시간·방 현황이 실제 예약으로 움직이고, 재실 센서 값(시뮬레이션)과 공기 값도
 * 그 예약에 맞춰 바뀐다(예약 없는 재실이 생기지 않음).
 * <ul>
 *   <li>방마다 앞으로 {@code horizon} 동안의 이용을 미리 짜 둔다(빈 틈 → 이용 → 빈 틈). 문 여는 시간(9~22시)만</li>
 *   <li>이용마다 예약하는 때가 다르다: 대부분 15분~2시간 전, 일부는 하루 전, 일부는 그 자리에서 "지금 바로 쓰기"</li>
 *   <li>대표자·참여자는 가상 학생 중에서 그때 비어 있는 사람으로 고른다(대표자 1건·같은 시간 두 방 금지를 지킴)</li>
 *   <li>입실은 시작 8분 전~8분 뒤에 QR로. 입실이 열려야 방에 사람이 생긴다. 퇴실은 끝나기 몇 분 전 버튼, 가끔 일찍,
 *       가끔은 버튼 없이 끝 시각(core 자동 퇴실)</li>
 *   <li>core가 먼저 끝낸 예약(관리자 강제 퇴실·자리 비움)과 실제 학생(팀원)의 예약도 1분마다 core 방 현황으로 맞춘다</li>
 *   <li>다시 켜지면 가상 학생의 끝나지 않은 예약을 core에서 읽어 이어 간다</li>
 * </ul>
 * 로그에는 방 번호·예약 번호·결과만 남긴다(토큰·이름·학번 없음).
 */
public class StudentSimulator {

    private static final Logger log = LoggerFactory.getLogger(StudentSimulator.class);
    private static final Duration SLOT = Duration.ofMinutes(30);
    /** 사람을 바꿔 다시 해 볼 만한 거절(정지·이미 다른 예약이 있음) */
    private static final Set<String> RETRY = Set.of("USER_SUSPENDED", "PARTICIPANT_SUSPENDED",
            "OWNER_HAS_ACTIVE_RESERVATION", "PARTICIPANT_HAS_ACTIVE_RESERVATION", "PARTICIPANT_TIME_TAKEN");

    /** 가상 학생이 그 이용을 어떻게 마칠지(미리 정해 둠) */
    public enum Plan { SHOW, CANCEL, NO_SHOW }

    public enum Kind { ADVANCE, INSTANT }

    public enum State { PLANNED, BOOKED, IN_USE, DONE, DROPPED }

    /** 숫자 설정. 확률은 0~1 */
    public record Settings(Duration horizon, int gapMin, int gapMax, int openHour, int closeHour,
                           double instantRate, double dayAheadRate, double cancelRate, double noShowRate,
                           double earlyLeaveRate, double autoCheckoutRate, double extendRate) {

        public static Settings defaults() {
            return new Settings(Duration.ofHours(26), 10, 50, 9, 22, 0.25, 0.1, 0.08, 0.04, 0.3, 0.2, 0.12);
        }
    }

    /** 방을 바꿀 곳(공기 시뮬레이션): 입실·퇴실 */
    public interface Rooms {
        void enter(int roomId, int people, Instant until);

        void leave(int roomId);
    }

    /** 화면·확인용으로 내보내는 이용 하나(이름·학번 없음) */
    public record VisitView(long id, int roomId, Kind kind, Plan plan, State state, Instant bookAt, Instant startAt,
                            Instant endAt, Instant arriveAt, Instant leaveAt, int people, Long reservationId,
                            boolean extend, boolean leaveEarly, boolean autoCheckout) {
    }

    private static final class Visit {
        final long id;
        final int room;
        final Kind kind;
        Plan plan;
        State state = State.PLANNED;
        Instant bookAt;
        Instant start;
        Instant end;
        Instant cancelAt;
        Instant arriveAt;
        Instant leaveAt;
        final int people;
        final boolean early;
        final boolean auto;
        int extendMinutes;
        boolean extendTried;
        int tries;
        Long reservationId;
        String accessCode;
        Student owner;
        Instant fixedEnd;     // 지금 바로 들어오기: 끝 시각을 정해 둠
        List<Student> participants = List.of();
        final int lenMinutes;

        Visit(long id, int room, Kind kind, int people, int lenMinutes, boolean early, boolean auto) {
            this.id = id;
            this.room = room;
            this.kind = kind;
            this.people = people;
            this.lenMinutes = lenMinutes;
            this.early = early;
            this.auto = auto;
        }

        boolean live() {
            return state == State.BOOKED || state == State.IN_USE;
        }

        VisitView view() {
            return new VisitView(id, room, kind, plan, state, bookAt, start, end, arriveAt, leaveAt, people,
                    reservationId, extendMinutes > 0, early, auto);
        }
    }

    private final StudentDesk desk;
    private final Rooms rooms;
    private final List<Student> students;
    private final Map<Integer, int[]> people;
    private final Settings s;
    private final ZoneId zone;
    private final Random random;
    private final List<Visit> visits = new ArrayList<>();
    private final Map<Integer, Instant> cursor = new HashMap<>();
    private final Map<Long, Instant> resting = new HashMap<>();
    private final Set<Long> noShowed = new HashSet<>();
    private final Set<Integer> foreign = new HashSet<>();
    private long seq;
    private boolean recovered;
    private boolean recoverWarned;
    private Instant lastReconcile;

    /**
     * @param people 방 번호 → {최소, 최대} 인원(대표자 포함, core 방 인원 규칙과 같게). 이 방들만 시뮬레이션한다
     */
    public StudentSimulator(StudentDesk desk, Rooms rooms, List<Student> students, Map<Integer, int[]> people,
                            Settings settings, ZoneId zone, long seed) {
        this.desk = desk;
        this.rooms = rooms;
        this.students = List.copyOf(students);
        this.people = new LinkedHashMap<>(people);
        this.s = settings;
        this.zone = zone;
        this.random = new Random(seed);
    }

    public Set<Integer> roomIds() {
        return people.keySet();
    }

    /** 한 번 돌기(10초마다). 실패해도 다음 번에 다시 한다 */
    public synchronized void tick(Instant now) {
        if (!recovered) {
            recover(now);
            if (!recovered) {
                return;
            }
        }
        if (lastReconcile == null || !now.isBefore(lastReconcile.plusSeconds(60))) {
            lastReconcile = now;
            reconcile(now);
        }
        plan(now);
        visits.stream().filter(v -> v.state != State.DONE && v.state != State.DROPPED)
                .sorted(Comparator.comparing(v -> v.bookAt)).toList()
                .forEach(v -> act(v, now));
        visits.removeIf(v -> (v.state == State.DONE || v.state == State.DROPPED)
                && v.end != null && v.end.isBefore(now.minus(Duration.ofHours(3))));
    }

    /** 앞으로의 이용과 지금 쓰는 중인 이용 (확인·영상 준비용) */
    public synchronized List<VisitView> plan() {
        return visits.stream().filter(v -> v.state != State.DONE && v.state != State.DROPPED)
                .sorted(Comparator.comparing((Visit v) -> v.start).thenComparing(v -> v.room))
                .map(Visit::view).toList();
    }

    /**
     * 시연용: 이 방에 가상 학생이 지금 바로 들어오게 한다(지금 바로 쓰기 → 몇 초 안에 QR 입실, 1시간).
     * 이미 누가 쓰는 중이거나 곧 예약이 있으면 false
     */
    public synchronized boolean walkIn(int roomId, Instant now) {
        if (!people.containsKey(roomId) || foreign.contains(roomId)) {
            return false;
        }
        if (visits.stream().anyMatch(v -> v.room == roomId && v.state == State.IN_USE)) {
            return false;
        }
        // 이 방에 이미 잡힌 다음 예약 전까지만 쓴다. 35분도 안 남았으면 안 들어온다
        Instant nextStart = visits.stream().filter(v -> v.room == roomId && v.state == State.BOOKED)
                .map(v -> v.start).min(Comparator.naturalOrder()).orElse(null);
        Instant end = ceilSlot(now.plus(Duration.ofMinutes(60)));
        if (nextStart != null && nextStart.isBefore(end)) {
            end = Instant.ofEpochSecond(nextStart.getEpochSecond() / SLOT.getSeconds() * SLOT.getSeconds());
        }
        if (end.isAfter(closeOf(now))) {
            end = closeOf(now);
        }
        if (Duration.between(now, end).toMinutes() < 35) {
            return false;
        }
        Visit v = new Visit(++seq, roomId, Kind.INSTANT, between(people.get(roomId)),
                (int) Duration.between(now, end).toMinutes(), false, false);
        v.plan = Plan.SHOW;
        v.bookAt = now;
        v.start = now;
        v.end = end;
        v.fixedEnd = end;
        v.arriveAt = now;
        v.leaveAt = v.end.minus(Duration.ofMinutes(between(1, 4)));
        // 미리 짜 둔 것 중 이 시간과 겹치는 것(아직 예약 전)은 뺀다
        visits.removeIf(o -> o.room == roomId && o.state == State.PLANNED && o.start.isBefore(v.end.plus(Duration.ofMinutes(10))));
        visits.add(v);
        act(v, now);   // 지금 바로 쓰기 예약
        act(v, now);   // 바로 QR 입실
        return v.state == State.BOOKED || v.state == State.IN_USE;
    }

    // ---- 다시 켜졌을 때 이어 가기

    private void recover(Instant now) {
        List<Visit> found = new ArrayList<>();
        try {
            for (Student st : students) {
                for (Booked b : desk.active(st)) {
                    if (!people.containsKey(b.roomId())) {
                        continue;   // 가상 학생이 실제 방(1번)에 잡은 예약은 건드리지 않는다
                    }
                    Visit v = new Visit(++seq, b.roomId(), Kind.ADVANCE, Math.max(1, b.headcount()),
                            (int) Duration.between(b.startAt(), b.endAt()).toMinutes(), false, false);
                    v.plan = Plan.SHOW;
                    v.owner = st;
                    v.bookAt = now;
                    v.start = b.startAt();
                    v.end = b.endAt();
                    v.reservationId = b.id();
                    v.accessCode = b.accessCode();
                    v.leaveAt = v.end.minus(Duration.ofMinutes(between(1, 4)));
                    if ("IN_USE".equals(b.status())) {
                        v.state = State.IN_USE;
                        v.arriveAt = now;
                        rooms.enter(v.room, v.people, v.leaveAt);
                    } else {
                        v.state = State.BOOKED;
                        Instant a = v.start.minus(Duration.ofMinutes(between(0, 8)));
                        v.arriveAt = a.isBefore(now) ? now : a;
                    }
                    found.add(v);
                }
            }
        } catch (RuntimeException e) {
            if (!recoverWarned) {
                recoverWarned = true;   // core가 꺼져 있는 동안 10초마다 찍지 않게 처음 한 번만
                log.warn("가상 학생 시작 못 함, core가 답할 때까지 다시 해 봄 ({})",
                        e instanceof Refused r ? r.code() : e.getClass().getSimpleName());
            }
            return;
        }
        visits.addAll(found);
        for (Visit v : found) {
            cursor.merge(v.room, v.end, (a, b) -> a.isAfter(b) ? a : b);
        }
        recovered = true;
        log.info("가상 학생 시작: 학생 {}명, 방 {}, 이어 가는 예약 {}건", students.size(), people.keySet(), found.size());
    }

    // ---- core 방 현황과 맞추기

    private void reconcile(Instant now) {
        Map<Integer, RoomNow> now0;
        try {
            now0 = desk.rooms();
        } catch (RuntimeException e) {
            return;
        }
        for (int room : people.keySet()) {
            RoomNow r = now0.get(room);
            if (r == null) {
                continue;
            }
            boolean inUse = "IN_USE".equals(r.status());
            Visit mine = visits.stream().filter(v -> v.room == room && v.state == State.IN_USE).findFirst().orElse(null);
            if (mine != null && !inUse && now.isAfter(later(mine.arriveAt, mine.start).plusSeconds(30))) {
                // core가 먼저 끝냄(관리자 강제 퇴실·자리 비움·시간 끝). 사람도 나간 것으로
                mine.state = State.DONE;
                rooms.leave(room);
                log.info("가상 학생 예약 {} core가 먼저 끝냄 (방 {})", mine.reservationId, room);
            } else if (mine == null && inUse && !foreign.contains(room)) {
                // 가상 학생이 아닌 사람(팀원)이 이 방을 쓰는 중 → 재실·공기도 그 예약에 맞춘다
                foreign.add(room);
                int[] p = people.get(room);
                rooms.enter(room, (p[0] + p[1] + 1) / 2, r.currentEndAt());
                log.info("방 {} 실제 예약 사용 중 → 재실 맞춤", room);
            } else if (foreign.contains(room) && !inUse) {
                foreign.remove(room);
                rooms.leave(room);
            }
        }
    }

    // ---- 미리 짜 두기

    private void plan(Instant now) {
        Instant until = now.plus(s.horizon());
        for (int room : people.keySet()) {
            Instant c = cursor.getOrDefault(room, now);
            if (c.isBefore(now)) {
                c = now;
            }
            while (c.isBefore(until)) {
                Visit v = next(room, c, now);
                visits.add(v);
                c = v.end.plus(Duration.ofMinutes(v.extendMinutes));
            }
            cursor.put(room, c);
        }
    }

    private Visit next(int room, Instant after, Instant now) {
        Instant start = after.plus(Duration.ofMinutes(between(s.gapMin(), s.gapMax())));
        int len = pick(60, 60, 90, 90, 120, 120, 120);
        if (!openAt(start, 60)) {
            start = nextOpen(start).plus(Duration.ofMinutes(between(0, 20)));
        }
        long room2close = minutesToClose(start);
        len = (int) Math.min(len, room2close);
        len = Math.max(60, len - len % 30);
        boolean instant = random.nextDouble() < s.instantRate();
        boolean early = random.nextDouble() < s.earlyLeaveRate();
        boolean auto = !early && random.nextDouble() < s.autoCheckoutRate();
        Visit v = new Visit(++seq, room, instant ? Kind.INSTANT : Kind.ADVANCE, between(people.get(room)), len, early, auto);
        if (instant) {
            v.start = start;
            v.end = ceilSlot(start.plus(Duration.ofMinutes(len)).minus(Duration.ofMinutes(29)));
            v.bookAt = start;
            v.plan = Plan.SHOW;
        } else {
            v.start = ceilSlot(start);
            v.end = v.start.plus(Duration.ofMinutes(len));
            long ahead = random.nextDouble() < s.dayAheadRate() ? between(12 * 60, 24 * 60) : between(15, 120);
            Instant book = v.start.minus(Duration.ofMinutes(ahead));
            v.bookAt = book.isBefore(now) ? now : book;
            double r = random.nextDouble();
            v.plan = r < s.cancelRate() ? Plan.CANCEL : r < s.cancelRate() + s.noShowRate() ? Plan.NO_SHOW : Plan.SHOW;
            long room4cancel = Math.max(1, Duration.between(v.bookAt, v.start).toMinutes() - 6);
            v.cancelAt = v.bookAt.plus(Duration.ofMinutes(Math.min(room4cancel, between(5, 60))));
        }
        if (v.end.isAfter(closeOf(v.start))) {
            v.end = closeOf(v.start);
        }
        v.arriveAt = instant ? v.start : v.start.plus(Duration.ofMinutes(between(-8, 8)));
        if (!instant && random.nextDouble() < s.extendRate() && !early && !auto
                && v.end.plus(Duration.ofMinutes(60)).isBefore(closeOf(v.start).plusSeconds(1))
                && Duration.between(v.start, v.end).toMinutes() <= 90) {
            v.extendMinutes = Math.min(120 - (int) Duration.between(v.start, v.end).toMinutes(), pick(30, 30, 60));
        }
        v.leaveAt = leaveTime(v);
        return v;
    }

    private Instant leaveTime(Visit v) {
        if (v.auto) {
            return v.end;
        }
        if (v.early) {
            long stay = Duration.between(v.arriveAt, v.end).toMinutes();
            long cut = Math.min(Math.max(5, stay - 20), between(15, 40));
            return v.end.minus(Duration.ofMinutes(cut));
        }
        return v.end.minus(Duration.ofMinutes(between(1, 4)));
    }

    // ---- 때가 된 일 하기

    private void act(Visit v, Instant now) {
        try {
            switch (v.state) {
                case PLANNED -> {
                    if (!now.isBefore(v.bookAt)) {
                        book(v, now);
                    }
                }
                case BOOKED -> booked(v, now);
                case IN_USE -> inUse(v, now);
                default -> {
                }
            }
        } catch (Refused e) {
            log.info("가상 학생 방 {} 예약 {} 거절 {}", v.room, v.reservationId, e.code());
            if (v.state == State.PLANNED) {
                v.state = State.DROPPED;
            } else if (v.state == State.BOOKED && v.plan == Plan.CANCEL) {
                v.state = State.DONE;
            }
        } catch (RuntimeException e) {
            log.warn("가상 학생 방 {} core 부르기 실패 ({})", v.room, e.getClass().getSimpleName());
        }
    }

    private void book(Visit v, Instant now) {
        if (v.kind == Kind.ADVANCE && !now.isBefore(v.start.minus(Duration.ofMinutes(2)))) {
            v.state = State.DROPPED;   // 꺼져 있다가 때를 놓침
            return;
        }
        if (v.kind == Kind.INSTANT && now.isAfter(v.start.plus(Duration.ofMinutes(10)))) {
            v.state = State.DROPPED;
            return;
        }
        if (foreign.contains(v.room) && v.kind == Kind.INSTANT) {
            v.state = State.DROPPED;
            return;
        }
        if (v.owner == null && !pickPeople(v, now)) {
            v.state = State.DROPPED;
            log.info("가상 학생 방 {} 비어 있는 학생이 모자라 건너뜀", v.room);
            return;
        }
        List<String> ids = v.participants.stream().map(Student::studentId).toList();
        Booked b;
        try {
            if (v.kind == Kind.INSTANT) {
                Instant end = v.fixedEnd != null ? v.fixedEnd
                        : ceilSlot(now.plus(Duration.ofMinutes(v.lenMinutes)).minus(Duration.ofMinutes(29)));
                if (end.isAfter(closeOf(now))) {
                    end = ceilSlot(closeOf(now).minus(SLOT));
                }
                b = desk.startNow(v.owner, v.room, end, ids, "sim-" + v.id);
            } else {
                b = desk.book(v.owner, v.room, v.start, v.end, ids, "sim-" + v.id);
            }
        } catch (Refused e) {
            String c = e.code();
            if (RETRY.contains(c) && ++v.tries < 3) {
                if ("USER_SUSPENDED".equals(c)) {
                    resting.put(v.owner.memberId(), now.plus(Duration.ofHours(24)));
                } else if ("OWNER_HAS_ACTIVE_RESERVATION".equals(c)) {
                    resting.put(v.owner.memberId(), now.plus(Duration.ofHours(2)));
                }
                v.owner = null;   // 다음 번에 다른 사람들로
                return;
            }
            throw e;
        }
        v.reservationId = b.id();
        v.accessCode = b.accessCode();
        v.start = b.startAt();
        v.end = b.endAt();
        v.state = State.BOOKED;
        if (v.kind == Kind.INSTANT) {
            v.arriveAt = v.fixedEnd != null ? now : now.plus(Duration.ofSeconds(between(30, 240)));
            v.leaveAt = leaveTime(v);
        }
        log.info("가상 학생 예약 {} 방 {} {}~{} {}명 ({})", b.id(), v.room, hm(v.start), hm(v.end), v.people,
                v.kind == Kind.INSTANT ? "지금 바로" : "미리");
    }

    private void booked(Visit v, Instant now) {
        if (v.plan == Plan.CANCEL) {
            if (!now.isBefore(v.cancelAt)) {
                desk.cancel(v.owner, v.reservationId);
                v.state = State.DONE;
                log.info("가상 학생 예약 {} 취소 (방 {})", v.reservationId, v.room);
            }
            return;
        }
        if (v.plan == Plan.NO_SHOW) {
            if (now.isAfter(v.start.plus(Duration.ofMinutes(16)))) {
                v.state = State.DONE;   // core가 노쇼로 바꿈
                noShowed.add(v.owner.memberId());
                log.info("가상 학생 예약 {} 노쇼 (방 {})", v.reservationId, v.room);
            }
            return;
        }
        if (now.isBefore(v.arriveAt)) {
            return;
        }
        if (now.isAfter(v.start.plus(Duration.ofMinutes(14)))) {
            v.state = State.DONE;   // 입실을 끝내 못 함 → core가 노쇼로 바꿈
            return;
        }
        if (v.accessCode == null || v.accessCode.isBlank()) {
            v.state = State.DONE;
            return;
        }
        if (desk.checkIn(v.room, v.accessCode, "sim-qr-" + v.reservationId + "-" + now.getEpochSecond())) {
            v.state = State.IN_USE;
            v.arriveAt = now;
            if (!v.leaveAt.isAfter(now)) {
                v.leaveAt = leaveTime(v);
            }
            rooms.enter(v.room, v.people, v.leaveAt);
            log.info("가상 학생 예약 {} QR 입실 (방 {}, {}명)", v.reservationId, v.room, v.people);
        }
    }

    private void inUse(Visit v, Instant now) {
        if (v.extendMinutes > 0 && !v.extendTried && !now.isBefore(v.end.minus(Duration.ofMinutes(8)))) {
            v.extendTried = true;
            Instant to = v.end.plus(Duration.ofMinutes(v.extendMinutes));
            try {
                Booked b = desk.extend(v.owner, v.reservationId, to, "sim-ext-" + v.id);
                long before = v.reservationId;
                v.reservationId = b.id();
                v.accessCode = b.accessCode();
                v.end = b.endAt();
                v.leaveAt = v.end.minus(Duration.ofMinutes(between(1, 4)));
                rooms.enter(v.room, v.people, v.leaveAt);
                log.info("가상 학생 예약 {} 연장 → {} (방 {}, ~{})", before, b.id(), v.room, hm(v.end));
            } catch (Refused e) {
                log.info("가상 학생 예약 {} 연장 거절 {} (방 {})", v.reservationId, e.code(), v.room);
            }
            return;
        }
        if (now.isBefore(v.leaveAt)) {
            return;
        }
        if (!v.auto) {
            try {
                desk.checkout(v.owner, v.reservationId);
            } catch (Refused e) {
                // 이미 core가 끝냄
            }
        }
        v.state = State.DONE;
        rooms.leave(v.room);
        log.info("가상 학생 예약 {} 퇴실 (방 {}, {})", v.reservationId, v.room, v.auto ? "끝 시각" : "버튼");
    }

    // ---- 사람 고르기

    /** core 규칙: 한 사람(대표자·참여자 모두)은 끝나지 않은 예약 1건만(V13). 그래서 예약이 살아 있는 사람은 다 뺀다 */
    private boolean pickPeople(Visit v, Instant now) {
        Set<Long> busy = new HashSet<>();
        for (Visit o : visits) {
            if (o == v || !o.live() || o.owner == null) {
                continue;
            }
            busy.add(o.owner.memberId());
            o.participants.forEach(p -> busy.add(p.memberId()));
        }
        List<Student> free = new ArrayList<>(students.stream()
                .filter(st -> !busy.contains(st.memberId()))
                .filter(st -> resting.getOrDefault(st.memberId(), Instant.MIN).isBefore(now))
                .toList());
        java.util.Collections.shuffle(free, random);
        Student owner = free.stream()
                .filter(st -> v.plan != Plan.NO_SHOW || !noShowed.contains(st.memberId()))
                .findFirst().orElse(null);
        if (owner == null || free.size() < v.people) {
            return false;
        }
        free.remove(owner);
        v.owner = owner;
        v.participants = List.copyOf(free.subList(0, v.people - 1));
        return true;
    }

    // ---- 시간 계산

    private Instant ceilSlot(Instant t) {
        long sec = t.getEpochSecond();
        long slot = SLOT.getSeconds();
        long up = (sec + slot - 1) / slot * slot;
        return Instant.ofEpochSecond(up);
    }

    private boolean openAt(Instant t, int minutes) {
        ZonedDateTime z = t.atZone(zone);
        return z.getHour() >= s.openHour() && minutesToClose(t) >= minutes;
    }

    private long minutesToClose(Instant t) {
        ZonedDateTime z = t.atZone(zone);
        if (z.getHour() < s.openHour() || z.getHour() >= s.closeHour()) {
            return 0;
        }
        return (s.closeHour() - z.getHour()) * 60L - z.getMinute();
    }

    private Instant closeOf(Instant t) {
        return t.atZone(zone).withHour(s.closeHour()).withMinute(0).withSecond(0).withNano(0).toInstant();
    }

    private Instant nextOpen(Instant t) {
        ZonedDateTime z = t.atZone(zone).withMinute(0).withSecond(0).withNano(0).withHour(s.openHour());
        if (!z.toInstant().isAfter(t)) {
            z = z.plusDays(1);
        }
        return z.toInstant();
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private String hm(Instant t) {
        ZonedDateTime z = t.atZone(zone);
        return "%02d:%02d".formatted(z.getHour(), z.getMinute());
    }

    private int between(int[] range) {
        return between(range[0], range[1]);
    }

    private int between(int lo, int hi) {
        return hi <= lo ? lo : lo + random.nextInt(hi - lo + 1);
    }

    private int pick(int... options) {
        return options[random.nextInt(options.length)];
    }

    /** 테스트용: 지금 짜 둔 이용 수 */
    int size() {
        return visits.size();
    }
}
