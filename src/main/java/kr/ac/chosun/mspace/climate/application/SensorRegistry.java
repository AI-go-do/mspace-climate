package kr.ac.chosun.mspace.climate.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MQTT로 본 공기 센서와 어느 방에 붙였는지. 자리(spot) → 방은 설정이 먼저, 없으면 받은 순서대로 빈 방에 붙인다.
 * 한 방에는 센서 하나만 붙인다(값이 섞여 출렁이지 않게). 방이 모자라면 남는 센서는 붙이지 않고 목록에만 둔다.
 */
public class SensorRegistry {

    public record Sensor(String id, String spot, String profile, Integer roomId, Instant lastAt, Double co2,
                         Double temperatureC, Double humidityPct) {
    }

    private final List<Integer> rooms;
    private final Map<String, Integer> fixed;
    private final Map<String, Integer> bySpot = new LinkedHashMap<>();
    private final Map<String, Sensor> sensors = new LinkedHashMap<>();

    public SensorRegistry(List<Integer> rooms, Map<String, Integer> fixed) {
        this.rooms = List.copyOf(rooms);
        this.fixed = fixed == null ? Map.of() : Map.copyOf(fixed);
    }

    /** 이 자리의 방 번호. 붙일 방이 없으면 null */
    public synchronized Integer roomFor(String spot) {
        String key = spot == null || spot.isBlank() ? "(자리 없음)" : spot;
        Integer room = bySpot.get(key);
        if (room != null) {
            return room;
        }
        room = fixed.get(key);
        if (room == null) {
            room = rooms.stream().filter(r -> !bySpot.containsValue(r) && !fixed.containsValue(r)).findFirst()
                    .orElse(null);
        }
        if (room != null) {
            bySpot.put(key, room);
        }
        return room;
    }

    public synchronized void seen(String id, String spot, String profile, Integer roomId, Instant at, Double co2,
                                  Double t, Double h) {
        Sensor old = sensors.get(id);
        sensors.put(id, new Sensor(id, spot, profile, roomId, at,
                co2 != null ? co2 : old == null ? null : old.co2(),
                t != null ? t : old == null ? null : old.temperatureC(),
                h != null ? h : old == null ? null : old.humidityPct()));
    }

    public synchronized List<Sensor> list() {
        List<Sensor> out = new ArrayList<>(sensors.values());
        out.sort(Comparator.comparing((Sensor s) -> s.roomId() == null ? Integer.MAX_VALUE : s.roomId())
                .thenComparing(Sensor::id));
        return out;
    }
}
