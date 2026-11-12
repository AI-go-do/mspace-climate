package kr.ac.chosun.mspace.climate.application;

import java.time.Instant;

/**
 * core POST /api/sensor-events 본문에서 쓰는 칸만 (게이트웨이가 사본을 줌).
 * kind CO2: ppm·temperatureC·humidityPct(선택), OCCUPANCY: occupied
 */
public record SensorReading(String eventId, int roomId, String kind, Boolean occupied, Integer ppm,
                            Double temperatureC, Double humidityPct, Instant observedAt) {
}
