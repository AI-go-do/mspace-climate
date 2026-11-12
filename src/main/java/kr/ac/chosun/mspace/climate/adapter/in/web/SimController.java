package kr.ac.chosun.mspace.climate.adapter.in.web;

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
    private final JwtVerifier jwt;
    private final AdminChecker admins;
    private final LiveFeed live;

    SimController(ObjectProvider<SimFeeder> feeder, JwtVerifier jwt, AdminChecker admins, LiveFeed live) {
        this.feeder = feeder;
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
        if (!f.trigger(roomId, kind)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "없는 방이에요");
        }
        live.publish("tick", Map.of("roomId", roomId));
        return Map.of("roomId", roomId, "kind", kind.name(), "minutes", 20);
    }
}
