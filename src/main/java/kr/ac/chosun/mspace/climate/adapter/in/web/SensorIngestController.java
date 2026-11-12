package kr.ac.chosun.mspace.climate.adapter.in.web;

import java.time.OffsetDateTime;
import java.util.Map;
import kr.ac.chosun.mspace.climate.application.ReadingIntake;
import kr.ac.chosun.mspace.climate.application.SensorReading;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 게이트웨이가 넘겨 주는 센서 값 사본 (core가 장치 토큰을 확인하고 받아 준 것만 온다).
 * /internal/** 은 게이트웨이 밖으로 열지 않는다(게이트웨이는 /api/climate/** 만 여기로 보냄). 컨테이너 포트도 밖에 안 연다.
 */
@RestController
class SensorIngestController {

    private final ReadingIntake intake;

    SensorIngestController(ReadingIntake intake) {
        this.intake = intake;
    }

    @PostMapping("/internal/sensor-events")
    Map<String, String> ingest(@RequestBody Body b) {
        if (b == null || b.roomId() == null || b.kind() == null) {
            return Map.of("result", "IGNORED");
        }
        boolean ok = intake.mirrored(new SensorReading(b.eventId(), b.roomId(), b.kind(), b.occupied(), b.ppm(),
                b.temperatureC(), b.humidityPct(), parse(b.observedAt())));
        return Map.of("result", ok ? "STORED" : "IGNORED");
    }

    private static java.time.Instant parse(String s) {
        try {
            return s == null ? null : OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    record Body(String eventId, Integer roomId, String kind, Boolean occupied, Integer ppm, Double temperatureC,
                Double humidityPct, String observedAt) {
    }
}
