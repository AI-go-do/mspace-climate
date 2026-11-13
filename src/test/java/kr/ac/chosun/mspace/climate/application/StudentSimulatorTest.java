package kr.ac.chosun.mspace.climate.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.chosun.mspace.climate.application.StudentDesk.Booked;
import kr.ac.chosun.mspace.climate.application.StudentDesk.Refused;
import kr.ac.chosun.mspace.climate.application.StudentDesk.RoomNow;
import kr.ac.chosun.mspace.climate.application.StudentDesk.Student;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 가상 학생: core 규칙을 지키며 예약·입실·퇴실하고, 재실이 예약과 늘 맞는지 */
class StudentSimulatorTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Instant T0 = Instant.parse("2026-11-13T23:00:00Z"); // 11/14 08:00 KST
    static final Map<Integer, int[]> PEOPLE = new LinkedHashMap<>(Map.of(2, new int[] {3, 5}, 3, new int[] {3, 5},
            4, new int[] {3, 5}, 5, new int[] {5, 8}));

    static List<Student> students(int n) {
        List<Student> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(new Student(1000 + i, "999800%02d".formatted(i)));
        }
        return out;
    }

    /** 공기 시뮬레이션 대신: 방에 사람이 있는지만 기억 */
    static final class FakeRooms implements StudentSimulator.Rooms {
        final Map<Integer, Integer> in = new HashMap<>();
        int enters;

        @Override
        public void enter(int roomId, int people, Instant until) {
            in.put(roomId, people);
            enters++;
        }

        @Override
        public void leave(int roomId) {
            in.remove(roomId);
        }
    }

    @Test
    @DisplayName("사흘 동안 가상 학생은 core 규칙(30분 칸·2시간·인원·1인 1건·겹침 없음)을 어기지 않고, 재실은 늘 입실한 예약과 맞는다")
    void keepsRulesAndOccupancyMatchesReservations() {
        // given
        FakeCore core = new FakeCore(PEOPLE);
        FakeRooms rooms = new FakeRooms();
        StudentSimulator sim = new StudentSimulator(core, rooms, students(60), PEOPLE,
                StudentSimulator.Settings.defaults(), SEOUL, 7);
        // when: 10초마다 사흘
        int mismatches = 0;
        for (Instant t = T0; t.isBefore(T0.plus(Duration.ofDays(3))); t = t.plusSeconds(10)) {
            core.now = t;
            core.sweep();
            sim.tick(t);
            for (int room : PEOPLE.keySet()) {
                if (rooms.in.containsKey(room) != core.inUse(room)) {
                    mismatches++;
                }
            }
        }
        // then
        assertThat(core.ruleBreaks).isEmpty();
        assertThat(mismatches).isZero();
        assertThat(core.created).isGreaterThan(40);         // 방 4개 × 하루 여러 번 × 사흘
        assertThat(core.count("CHECKED_OUT") + core.count("AUTO_CHECKED_OUT")).isGreaterThan(30);
        assertThat(core.count("CANCELED")).isPositive();
        assertThat(core.count("NO_SHOW")).isPositive();
        assertThat(core.extended).isPositive();
        assertThat(core.instant).isPositive();
        assertThat(core.roomsTouched).isEqualTo(PEOPLE.keySet());   // 1번 방은 안 건드림
    }

    @Test
    @DisplayName("밤(22시~9시)에는 예약을 시작하지 않는다")
    void nothingAtNight() {
        // given
        FakeCore core = new FakeCore(PEOPLE);
        StudentSimulator sim = new StudentSimulator(core, new FakeRooms(), students(60), PEOPLE,
                StudentSimulator.Settings.defaults(), SEOUL, 3);
        // when
        for (Instant t = T0; t.isBefore(T0.plus(Duration.ofDays(2))); t = t.plusSeconds(10)) {
            core.now = t;
            core.sweep();
            sim.tick(t);
        }
        // then
        for (FakeCore.Res r : core.all.values()) {
            int startHour = r.start.atZone(SEOUL).getHour();
            assertThat(startHour).isBetween(9, 21);
            assertThat(r.end.atZone(SEOUL).getHour() * 60 + r.end.atZone(SEOUL).getMinute()).isLessThanOrEqualTo(22 * 60);
        }
    }

    @Test
    @DisplayName("다시 켜지면 core에 남은 가상 학생 예약을 이어 간다: 사용 중이면 바로 재실, 끝나면 퇴실")
    void resumesAfterRestart() {
        // given: 사용 중인 예약 하나가 core에 있음
        FakeCore core = new FakeCore(PEOPLE);
        Instant noon = Instant.parse("2026-11-14T03:00:00Z");   // 12:00 KST
        core.now = noon;
        List<Student> list = students(60);
        Booked b = core.book(list.get(0), 3, noon, noon.plus(Duration.ofHours(1)),
                List.of(list.get(1).studentId(), list.get(2).studentId()), "x");
        core.checkIn(3, b.accessCode(), "e");
        FakeRooms rooms = new FakeRooms();
        // when: 새로 켜진 시뮬레이터가 한 번 돎
        StudentSimulator sim = new StudentSimulator(core, rooms, list, PEOPLE, StudentSimulator.Settings.defaults(), SEOUL, 5);
        sim.tick(noon.plusSeconds(10));
        // then
        assertThat(rooms.in).containsKey(3);
        // when: 끝날 때까지
        for (Instant t = noon.plusSeconds(20); t.isBefore(noon.plus(Duration.ofMinutes(65))); t = t.plusSeconds(10)) {
            core.now = t;
            core.sweep();
            sim.tick(t);
        }
        // then
        assertThat(core.all.get(b.id()).status).isIn("CHECKED_OUT", "AUTO_CHECKED_OUT");
    }

    @Test
    @DisplayName("가상 학생이 아닌 사람(팀원)이 가상 방을 쓰면 재실도 그 예약을 따른다")
    void followsTeammateReservation() {
        // given: 팀원 예약이 3번 방에서 사용 중
        FakeCore core = new FakeCore(PEOPLE);
        Instant t0 = Instant.parse("2026-11-14T05:00:00Z");   // 14:00 KST
        core.now = t0;
        Student mate = new Student(1, "20230001");
        Booked b = core.book(mate, 3, t0, t0.plus(Duration.ofHours(1)), List.of("20230002", "20230003"), "m");
        core.checkIn(3, b.accessCode(), "m");
        FakeRooms rooms = new FakeRooms();
        StudentSimulator sim = new StudentSimulator(core, rooms, students(60), PEOPLE, StudentSimulator.Settings.defaults(), SEOUL, 9);
        // when
        sim.tick(t0.plusSeconds(10));
        // then
        assertThat(rooms.in).containsKey(3);
        // when: 팀원이 퇴실
        core.checkout(mate, b.id());
        sim.tick(t0.plusSeconds(80));
        // then
        assertThat(rooms.in).doesNotContainKey(3);
    }

    @Test
    @DisplayName("지금 바로 들어오기: 빈 방이면 지금 바로 쓰기 예약을 하고 QR로 입실해 사람이 생긴다")
    void walkIn() {
        // given
        FakeCore core = new FakeCore(PEOPLE);
        Instant t0 = Instant.parse("2026-11-14T05:07:00Z");   // 14:07 KST
        core.now = t0;
        FakeRooms rooms = new FakeRooms();
        StudentSimulator sim = new StudentSimulator(core, rooms, students(60), PEOPLE, StudentSimulator.Settings.defaults(), SEOUL, 1);
        sim.tick(t0);
        // when: 빈 방 가운데 들어올 수 있는 방 하나에
        Integer room = null;
        for (int r : PEOPLE.keySet()) {
            if (!core.inUse(r) && sim.walkIn(r, t0.plusSeconds(5))) {
                room = r;
                break;
            }
        }
        // then: 바로 예약·입실
        assertThat(room).isNotNull();
        assertThat(core.inUse(room)).isTrue();
        assertThat(rooms.in).containsKey(room);
        assertThat(core.ruleBreaks).isEmpty();
    }

    /** core 대신: 예약 규칙을 같은 기준으로 확인하고, 어기면 기록 */
    static final class FakeCore implements StudentDesk {
        static final class Res {
            long id;
            int room;
            Instant start;
            Instant end;
            String status;
            long owner;
            Set<String> people = new HashSet<>();
            String code;
            boolean continued;
        }

        final Map<Integer, int[]> range;
        final Map<Long, Res> all = new LinkedHashMap<>();
        final Map<Long, String> sidOf = new HashMap<>();
        final List<String> ruleBreaks = new ArrayList<>();
        final Set<Integer> roomsTouched = new HashSet<>();
        Instant now = T0;
        long seq;
        int created;
        int extended;
        int instant;

        FakeCore(Map<Integer, int[]> range) {
            this.range = range;
        }

        boolean inUse(int room) {
            return all.values().stream().anyMatch(r -> r.room == room && r.status.equals("IN_USE"));
        }

        long count(String status) {
            return all.values().stream().filter(r -> r.status.equals(status)).count();
        }

        void sweep() {
            for (Res r : all.values()) {
                if (r.status.equals("IN_USE") && !now.isBefore(r.end)) {
                    r.status = "AUTO_CHECKED_OUT";
                } else if (r.status.equals("BOOKED") && now.isAfter(r.start.plus(Duration.ofMinutes(15)))) {
                    r.status = "NO_SHOW";
                }
            }
        }

        private Booked create(Student owner, int room, Instant start, Instant end, List<String> participants, boolean onSlot) {
            sidOf.put(owner.memberId(), owner.studentId());
            roomsTouched.add(room);
            if (onSlot && (start.getEpochSecond() % 1800 != 0)) {
                ruleBreaks.add("시작이 30분 칸 아님");
                throw new Refused(400, "RESERVATION_NOT_ON_SLOT");
            }
            if (end.getEpochSecond() % 1800 != 0 || !end.isAfter(start) || Duration.between(start, end).toMinutes() > 150) {
                ruleBreaks.add("끝 시각·길이 틀림 " + start + "~" + end);
                throw new Refused(400, "RESERVATION_INVALID_LENGTH");
            }
            if (onSlot && Duration.between(start, end).toMinutes() > 120) {
                ruleBreaks.add("2시간 넘음");
                throw new Refused(400, "RESERVATION_INVALID_LENGTH");
            }
            if (start.isBefore(now.minusSeconds(1))) {
                ruleBreaks.add("지난 시각");
                throw new Refused(400, "RESERVATION_IN_PAST");
            }
            int n = participants.size() + 1;
            int[] p = range.getOrDefault(room, new int[] {3, 5});
            if (n < p[0] || n > p[1]) {
                ruleBreaks.add("인원 " + n);
                throw new Refused(400, "HEADCOUNT_OUT_OF_RANGE");
            }
            Set<String> people = new HashSet<>(participants);
            people.add(owner.studentId());
            if (people.size() != n) {
                ruleBreaks.add("같은 학번 두 번");
                throw new Refused(400, "PARTICIPANT_DUPLICATE");
            }
            for (Res r : all.values()) {
                boolean live = r.status.equals("BOOKED") || r.status.equals("IN_USE");
                if (!live) {
                    continue;
                }
                if (r.room == room && r.start.isBefore(end) && start.isBefore(r.end)) {
                    throw new Refused(409, "RESERVATION_TIME_TAKEN");   // 다른 사람 예약과 겹침: 시뮬레이터가 건너뛰면 됨
                }
                if (r.people.contains(owner.studentId())) {
                    ruleBreaks.add("대표자 1건 어김");
                    throw new Refused(409, "OWNER_HAS_ACTIVE_RESERVATION");
                }
                for (String s : participants) {
                    if (r.people.contains(s)) {
                        ruleBreaks.add("참여자 1건 어김");
                        throw new Refused(409, "PARTICIPANT_HAS_ACTIVE_RESERVATION");
                    }
                }
            }
            Res r = new Res();
            r.id = ++seq;
            r.room = room;
            r.start = start;
            r.end = end;
            r.status = "BOOKED";
            r.owner = owner.memberId();
            r.people = people;
            r.code = "%06d".formatted(100000 + r.id);
            all.put(r.id, r);
            created++;
            return view(r);
        }

        private Booked view(Res r) {
            return new Booked(r.id, r.room, r.start, r.end, r.status, r.code, r.people.size());
        }

        private Res mine(Student owner, long id) {
            Res r = all.get(id);
            if (r == null || r.owner != owner.memberId()) {
                ruleBreaks.add("내 예약 아님 " + id);
                throw new Refused(404, "RESERVATION_NOT_FOUND");
            }
            return r;
        }

        @Override
        public Booked book(Student owner, int roomId, Instant startAt, Instant endAt, List<String> participants, String key) {
            return create(owner, roomId, startAt, endAt, participants, true);
        }

        @Override
        public Booked startNow(Student owner, int roomId, Instant endAt, List<String> participants, String key) {
            instant++;
            if (Duration.between(now, endAt).toMinutes() > 150) {
                ruleBreaks.add("즉시 시작 끝이 너무 멂");
            }
            return create(owner, roomId, now, endAt, participants, false);
        }

        @Override
        public void cancel(Student owner, long id) {
            Res r = mine(owner, id);
            if (!r.status.equals("BOOKED") || !now.isBefore(r.start)) {
                throw new Refused(409, "RESERVATION_NOT_CANCELABLE");
            }
            r.status = "CANCELED";
        }

        @Override
        public boolean checkIn(int roomId, String code, String eventId) {
            for (Res r : all.values()) {
                if (r.room == roomId && r.status.equals("BOOKED") && r.code.equals(code)) {
                    if (now.isBefore(r.start.minus(Duration.ofMinutes(10))) || !now.isBefore(r.end)) {
                        ruleBreaks.add("입실 시간 밖");
                        return false;
                    }
                    r.status = "IN_USE";
                    return true;
                }
            }
            return false;
        }

        @Override
        public void checkout(Student owner, long id) {
            Res r = mine(owner, id);
            if (!r.status.equals("IN_USE")) {
                throw new Refused(409, "RESERVATION_NOT_IN_USE");
            }
            r.status = "CHECKED_OUT";
        }

        @Override
        public Booked extend(Student owner, long id, Instant endAt, String key) {
            Res r = mine(owner, id);
            if (!r.status.equals("IN_USE") || r.continued || now.isBefore(r.end.minus(Duration.ofMinutes(10)))
                    || !now.isBefore(r.end)) {
                ruleBreaks.add("연장 때 아님");
                throw new Refused(409, "RESERVATION_EXTENSION_NOT_OPEN");
            }
            for (Res o : all.values()) {
                if (o != r && o.room == r.room && (o.status.equals("BOOKED") || o.status.equals("IN_USE"))
                        && o.start.isBefore(endAt) && now.isBefore(o.end)) {
                    throw new Refused(409, "RESERVATION_TIME_TAKEN");
                }
            }
            r.status = "CHECKED_OUT";
            Res n = new Res();
            n.id = ++seq;
            n.room = r.room;
            n.start = now;
            n.end = endAt;
            n.status = "IN_USE";
            n.owner = r.owner;
            n.people = r.people;
            n.code = "%06d".formatted(100000 + n.id);
            n.continued = true;
            all.put(n.id, n);
            extended++;
            return view(n);
        }

        @Override
        public List<Booked> active(Student owner) {
            return all.values().stream()
                    .filter(r -> r.owner == owner.memberId() && (r.status.equals("BOOKED") || r.status.equals("IN_USE")))
                    .map(this::view).toList();
        }

        @Override
        public Map<Integer, RoomNow> rooms() {
            Map<Integer, RoomNow> out = new HashMap<>();
            for (int room = 1; room <= 5; room++) {
                int id = room;
                Res cur = all.values().stream().filter(r -> r.room == id && (r.status.equals("BOOKED") || r.status.equals("IN_USE"))
                        && !now.isBefore(r.start) && now.isBefore(r.end)).findFirst().orElse(null);
                out.put(room, cur == null ? new RoomNow("AVAILABLE", null)
                        : new RoomNow(cur.status.equals("IN_USE") ? "IN_USE" : "RESERVED", cur.end));
            }
            return out;
        }
    }
}
