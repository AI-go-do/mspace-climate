package kr.ac.chosun.mspace.climate.adapter.in.web;

import kr.ac.chosun.mspace.climate.adapter.in.scheduler.StudentSimRunner;
import kr.ac.chosun.mspace.climate.application.StudentSimulator;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;
import kr.ac.chosun.mspace.climate.adapter.in.scheduler.SimFeeder;
import kr.ac.chosun.mspace.climate.application.RoomSimulator;
import kr.ac.chosun.mspace.climate.auth.AdminChecker;
import kr.ac.chosun.mspace.climate.auth.ApiException;
import kr.ac.chosun.mspace.climate.auth.JwtVerifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시연용: 시뮬레이션 방에 상황을 바로 일으킨다(재웅 11/13 "이상은 드물게, 보여 줄 땐 필요할 때 일부러").
 * POST /api/climate/sim/rooms/{roomId}/events {"kind":"HEAT"|"STUFFY"} → 20분 동안 온도(HEAT) 또는 CO2(STUFFY)가 오른다.
 * 관리자만. 시뮬레이션이 꺼져 있으면 404
 */
@RestController
class SimController {

    private final ObjectProvider<SimFeeder> feeder;
    private final ObjectProvider<StudentSimRunner> students;
    private final JwtVerifier jwt;
    private final AdminChecker admins;
    private final LiveFeed live;
    private final java.time.Clock clock;

    SimController(ObjectProvider<SimFeeder> feeder, ObjectProvider<StudentSimRunner> students, JwtVerifier jwt,
                  AdminChecker admins, LiveFeed live, java.time.Clock clock) {
        this.feeder = feeder;
        this.students = students;
        this.clock = clock;
        this.jwt = jwt;
        this.admins = admins;
        this.live = live;
    }

    @PostMapping("/api/climate/sim/rooms/{roomId}/events")
    Map<String, Object> event(@RequestHeader(name = "Authorization", required = false) String auth,
                              @PathVariable int roomId, @RequestBody(required = false) Map<String, String> body) {
        long member = jwt.memberId(auth);
        admins.requireAdmin(member, auth);
        SimFeeder f = feeder.getIfAvailable();
        if (f == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SIM_OFF", "시뮬레이션이 꺼져 있어요");
        }
        RoomSimulator.Event kind;
        try {
            kind = RoomSimulator.Event.valueOf(body == null ? "" : String.valueOf(body.get("kind")));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "kind는 HEAT(온도) 또는 STUFFY(CO2)예요");
        }
        // 가상 학생 방이 비어 있으면 가상 학생이 "지금 바로 쓰기"로 먼저 들어온다(core 실제 예약·QR 입실, 몇 초 안)
        StudentSimRunner st = students.getIfAvailable();
        boolean walkIn = st != null && st.students().roomIds().contains(roomId)
                && !f.simulator().occupied(roomId) && st.students().walkIn(roomId, clock.instant());
        if (!f.trigger(roomId, kind)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "없는 방이에요");
        }
        live.publish("tick", Map.of("roomId", roomId));
        return Map.of("roomId", roomId, "kind", kind.name(), "minutes", 20, "walkIn", walkIn);
    }

    /**
     * 가상 학생 이용 계획(지금 쓰는 중 + 앞으로). 영상 찍을 때 다음 입실·퇴실이 언제인지 보려고. 관리자만, 이름·학번 없음.
     * GET /api/climate/sim/students → [{"roomId":2,"kind":"ADVANCE","plan":"SHOW","state":"BOOKED","startAt":…}, …]
     */
    @GetMapping("/api/climate/sim/students")
    java.util.List<StudentSimulator.VisitView> students(@RequestHeader(name = "Authorization", required = false) String auth) {
        long member = jwt.memberId(auth);
        admins.requireAdmin(member, auth);
        StudentSimRunner st = students.getIfAvailable();
        if (st == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "STUDENTS_OFF", "가상 학생이 꺼져 있어요");
        }
        return st.students().plan();
    }

    /** 시연용: 이 방에 가상 학생이 지금 바로 들어오게(지금 바로 쓰기 → QR 입실, 1시간). 관리자만 */
    @PostMapping("/api/climate/sim/rooms/{roomId}/walk-in")
    Map<String, Object> walkIn(@RequestHeader(name = "Authorization", required = false) String auth,
                               @PathVariable int roomId) {
        long member = jwt.memberId(auth);
        admins.requireAdmin(member, auth);
        StudentSimRunner st = students.getIfAvailable();
        if (st == null || !st.students().roomIds().contains(roomId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "STUDENTS_OFF", "이 방은 가상 학생이 없어요");
        }
        if (!st.students().walkIn(roomId, clock.instant())) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_BUSY", "지금 쓰는 중이거나 곧 예약이 있어요");
        }
        return Map.of("roomId", roomId, "walkIn", true);
    }
}
