package kr.ac.chosun.mspace.climate.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 실제 공기 센서는 실습실 하나(1번 방)라, 2~5번 방 값은 1번 방 실제 값에서 만든다 (재웅 11/12 "시뮬레이션 느낌으로, 너무 큰 차이 안 나게").
 * 값 = 실제 값 + 방마다 고정 차이 + 방마다 주기가 다른 느린 흔들림(사인파). 난수가 아니라 시각으로 정해져 재시작해도 같다.
 * 실제 값이 오르면 다른 방도 같이 오른다. 화면·API에는 source=DERIVED로 표시한다(진짜 센서처럼 속이지 않음).
 *
 * @param sourceRoom 실제 센서가 있는 방
 * @param rooms      방 번호 → 차이
 */
public record RoomDeriver(int sourceRoom, Map<Integer, Offset> rooms) {

    /** 방마다 고정 차이와 흔들림 크기. period는 흔들림 한 바퀴(분) */
    public record Offset(double temp, double humidity, double co2, double tempWobble, double humidityWobble,
                         double co2Wobble, int period) {
    }

    public boolean derived(int roomId) {
        return rooms != null && rooms.containsKey(roomId);
    }

    /** 1번 방 실제 CO2 이벤트 하나 → 다른 방 이벤트들 */
    public List<SensorReading> derive(SensorReading real, Instant at) {
        List<SensorReading> out = new ArrayList<>();
        if (rooms == null || real.roomId() != sourceRoom || !"CO2".equals(real.kind())) {
            return out;
        }
        long minute = at.getEpochSecond() / 60;
        rooms.forEach((room, o) -> {
            double phase = 2 * Math.PI * ((minute + room * 13L) % Math.max(o.period(), 1)) / Math.max(o.period(), 1);
            double w = Math.sin(phase);
            double w2 = Math.sin(phase * 2 + room);
            Integer ppm = real.ppm() == null ? null
                    : (int) Math.round(Math.max(380, real.ppm() + o.co2() + o.co2Wobble() * w));
            Double t = real.temperatureC() == null ? null
                    : Math.round((real.temperatureC() + o.temp() + o.tempWobble() * w2) * 10) / 10.0;
            Double h = real.humidityPct() == null ? null
                    : Math.round(clamp(real.humidityPct() + o.humidity() + o.humidityWobble() * w, 0, 100) * 10) / 10.0;
            String id = real.eventId() == null ? null : real.eventId() + "-r" + room;
            out.add(new SensorReading(id, room, "CO2", null, ppm, t, h, real.observedAt()));
        });
        return out;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
