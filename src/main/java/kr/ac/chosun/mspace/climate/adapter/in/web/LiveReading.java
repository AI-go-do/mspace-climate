package kr.ac.chosun.mspace.climate.adapter.in.web;

import java.time.Instant;
import kr.ac.chosun.mspace.climate.application.SensorReading;

/** 실시간 연결로 보내는 새 값 한 줄 (이벤트 이름 reading) */
public record LiveReading(int roomId, String at, Integer co2, Double temperatureC, Double humidityPct, String source) {

    public static LiveReading of(SensorReading r, String source) {
        return new LiveReading(r.roomId(), Times.kst(r.observedAt() == null ? Instant.now() : r.observedAt()), r.ppm(),
                r.temperatureC(), r.humidityPct(), source);
    }
}
