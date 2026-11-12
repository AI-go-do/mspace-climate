package kr.ac.chosun.mspace.climate.adapter.in.web;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.climate.application.BoardModel;
import kr.ac.chosun.mspace.climate.application.SensorRegistry;
import kr.ac.chosun.mspace.climate.auth.JwtVerifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/climate/sensors: MQTT로 본 센서가 어느 방에 붙었는지와 보드 A 모델 최신 결과 (로그인 필요).
 * 자리 → 방을 고정하려면 이 목록을 보고 climate.mqtt.spots에 적는다. 기기 번호는 끝 4자리만 보여 준다
 */
@RestController
class SensorsController {

    private final SensorRegistry sensors;
    private final BoardModel model;
    private final JwtVerifier jwt;
    private final Clock clock;

    SensorsController(SensorRegistry sensors, BoardModel model, JwtVerifier jwt, Clock clock) {
        this.sensors = sensors;
        this.model = model;
        this.jwt = jwt;
        this.clock = clock;
    }

    @GetMapping("/api/climate/sensors")
    Map<String, Object> list(@RequestHeader(name = "Authorization", required = false) String auth) {
        jwt.memberId(auth);
        List<SensorView> out = sensors.list().stream().map(SensorView::of).toList();
        List<ModelView> models = model.all().stream().map(ModelView::of).toList();
        return Map.of("now", Times.kst(clock.instant()), "sensors", out, "models", models);
    }

    record SensorView(String device, String spot, String profile, Integer roomId, String lastAt, Double co2,
                      Double temperatureC, Double humidityPct) {
        static SensorView of(SensorRegistry.Sensor s) {
            String id = s.id().length() > 4 ? "…" + s.id().substring(s.id().length() - 4) : s.id();
            return new SensorView(id, s.spot(), s.profile(), s.roomId(), s.lastAt() == null ? null : Times.kst(s.lastAt()),
                    s.co2(), s.temperatureC(), s.humidityPct());
        }
    }

    record ModelView(String slug, String space, String slot, String receivedAt, Double co2Now,
                     List<BoardModel.Point> co2, Double tempNow, List<BoardModel.Point> temp, Double anomalyScore,
                     Double anomalyThreshold, boolean anomaly, String anomalyCause, Double occupiedProbability,
                     Double ventilationProbability) {
        static ModelView of(BoardModel.State s) {
            return new ModelView(s.slug(), s.space(), s.slot() == null ? null : Times.kst(s.slot()),
                    Times.kst(s.receivedAt()), s.co2Now(), s.co2(), s.tempNow(), s.temp(), s.anomalyScore(),
                    s.anomalyThreshold(), s.anomaly(), s.anomalyCause(), s.occupiedProbability(),
                    s.ventilationProbability());
        }
    }
}
