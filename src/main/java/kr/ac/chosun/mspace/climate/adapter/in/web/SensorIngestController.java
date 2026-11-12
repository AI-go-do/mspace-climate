package kr.ac.chosun.mspace.climate.adapter.in.web;

import java.time.OffsetDateTime;
import java.util.Map;
import kr.ac.chosun.mspace.climate.application.ClimateAgent;
import kr.ac.chosun.mspace.climate.application.ClimateProperties;
import kr.ac.chosun.mspace.climate.application.ClimateStore;
import kr.ac.chosun.mspace.climate.application.RoomDeriver;
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

    private final ClimateStore store;
    private final RoomDeriver deriver;
    private final LiveFeed live;
    private final ClimateAgent agent;

    SensorIngestController(ClimateStore store, ClimateProperties props, LiveFeed live, ClimateAgent agent) {
        this.agent = agent;
        this.store = store;
        this.live = live;
        this.deriver = props.derive() == null ? new RoomDeriver(0, null) : props.derive();
    }

    @PostMapping("/internal/sensor-events")
    Map<String, String> ingest(@RequestBody Body b) {
        if (b == null || b.roomId() == null || b.kind() == null) {
            return Map.of("result", "IGNORED");
        }
        if (deriver.derived(b.roomId()) && "CO2".equals(b.kind())) {
            // 이 방 공기 값은 실제 방에서 만든다(설정). 섞이지 않게 직접 온 값은 버린다
            return Map.of("result", "IGNORED");
        }
        var at = parse(b.observedAt());
        SensorReading real = new SensorReading(b.eventId(), b.roomId(), b.kind(), b.occupied(), b.ppm(),
                b.temperatureC(), b.humidityPct(), at);
        boolean ok = store.accept(real);
        if (ok) {
            push(real, "REAL");
            agent.tickRoom(real.roomId());
            for (SensorReading d : deriver.derive(real, at == null ? java.time.Instant.now() : at)) {
                if (store.accept(d)) {
                    push(d, "DERIVED");
                    agent.tickRoom(d.roomId());
                }
            }
        }
        return Map.of("result", ok ? "STORED" : "IGNORED");
    }

    /** 받은 그 순간 열린 대시보드로 보낸다 (공기 값만, 재실 이벤트는 다음 판단 때) */
    private void push(SensorReading r, String source) {
        if (!"CO2".equals(r.kind())) {
            return;
        }
        live.publish("reading", new Live(r.roomId(), Times.kst(r.observedAt() == null ? java.time.Instant.now()
                : r.observedAt()), r.ppm(), r.temperatureC(), r.humidityPct(), source));
    }

    record Live(int roomId, String at, Integer co2, Double temperatureC, Double humidityPct, String source) {
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
