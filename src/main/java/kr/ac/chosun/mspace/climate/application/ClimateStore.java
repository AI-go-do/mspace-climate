package kr.ac.chosun.mspace.climate.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.chosun.mspace.climate.domain.Metric;
import kr.ac.chosun.mspace.climate.domain.MetricHistory;
import kr.ac.chosun.mspace.climate.domain.Sample;

/**
 * 방별 센서 기록·재실·자동 제어 켬/끔. 메모리에 두고 SnapshotFile이 주기적으로 파일에 남긴다.
 * 방 하나씩 잠가서(synchronized on Room) 받기와 판단이 겹쳐도 안전하다.
 */
public class ClimateStore {

    /** 같은 eventId 재전송(장치 재시도) 거르기용으로 기억할 개수 */
    static final int SEEN_MAX = 5000;
    /** 센서 시각이 받은 시각보다 이만큼 넘게 미래면 받은 시각을 쓴다 (device room_env와 같은 처리) */
    static final long FUTURE_SEC = 30;

    private final Map<Integer, Room> rooms = new LinkedHashMap<>();
    private final Set<String> seen = new LinkedHashSet<>();
    private final Clock clock;

    public ClimateStore(ClimateProperties props, Clock clock) {
        this.clock = clock;
        props.rooms().keySet().stream().sorted().forEach(id -> rooms.put(id, new Room(id, props)));
    }

    /** 받으면 true. 모르는 방·겹친 eventId·늦게 온 값은 false */
    public boolean accept(SensorReading r) {
        Room room = rooms.get(r.roomId());
        if (room == null || !firstTime(r.eventId())) {
            return false;
        }
        Instant now = clock.instant();
        Instant at = r.observedAt() == null || r.observedAt().isAfter(now.plusSeconds(FUTURE_SEC)) ? now : r.observedAt();
        synchronized (room) {
            room.lastSignalAt = latest(room.lastSignalAt, at);
            if ("OCCUPANCY".equals(r.kind()) && r.occupied() != null) {
                if (room.occupiedAt == null || !at.isBefore(room.occupiedAt)) {
                    if (room.occupied == null || room.occupied != r.occupied()) {
                        room.occupiedSince = at;
                    }
                    room.occupied = r.occupied();
                    room.occupiedAt = at;
                }
                return true;
            }
            if (!"CO2".equals(r.kind())) {
                return false;
            }
            boolean any = false;
            if (r.ppm() != null && r.ppm() >= 0 && r.ppm() <= 10_000) {
                any |= room.history.get(Metric.CO2).add(at, r.ppm());
            }
            if (r.temperatureC() != null && r.temperatureC() >= -40 && r.temperatureC() <= 85) {
                any |= room.history.get(Metric.TEMPERATURE).add(at, r.temperatureC());
            }
            if (r.humidityPct() != null && r.humidityPct() >= 0 && r.humidityPct() <= 100) {
                any |= room.history.get(Metric.HUMIDITY).add(at, r.humidityPct());
            }
            if (any) {
                room.envAt = latest(room.envAt, at);
            }
            return any;
        }
    }

    private synchronized boolean firstTime(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return true;
        }
        if (!seen.add(eventId)) {
            return false;
        }
        if (seen.size() > SEEN_MAX) {
            var it = seen.iterator();
            it.next();
            it.remove();
        }
        return true;
    }

    private static Instant latest(Instant a, Instant b) {
        return a == null || b.isAfter(a) ? b : a;
    }

    public List<Integer> roomIds() {
        return List.copyOf(rooms.keySet());
    }

    public RoomView view(int roomId) {
        Room room = rooms.get(roomId);
        if (room == null) {
            return null;
        }
        synchronized (room) {
            Map<Metric, List<Sample>> h = new EnumMap<>(Metric.class);
            room.history.forEach((m, mh) -> h.put(m, mh.all()));
            return new RoomView(roomId, room.name, h, room.occupied, room.occupiedSince, room.envAt,
                    room.lastSignalAt, room.auto);
        }
    }

    /** 재실을 바깥(core 방 현황)에서 받아 넣는다. null이면 모름 */
    public void setOccupancy(int roomId, Boolean occupied, Instant at) {
        Room room = rooms.get(roomId);
        if (room == null) {
            return;
        }
        synchronized (room) {
            if (!java.util.Objects.equals(room.occupied, occupied)) {
                room.occupiedSince = at;
            }
            room.occupied = occupied;
            room.occupiedAt = at;
        }
    }

    public void setAuto(int roomId, boolean on) {
        Room room = rooms.get(roomId);
        if (room != null) {
            synchronized (room) {
                room.auto = on;
            }
        }
    }

    /** 스냅숏에서 되살리기 (값은 시간 순서로) */
    public void restore(int roomId, Metric metric, List<Sample> samples, Boolean auto) {
        Room room = rooms.get(roomId);
        if (room == null) {
            return;
        }
        synchronized (room) {
            samples.forEach(s -> room.history.get(metric).add(s.at(), s.value()));
            samples.stream().map(Sample::at).max(Instant::compareTo).ifPresent(at -> room.envAt = latest(room.envAt, at));
            if (auto != null) {
                room.auto = auto;
            }
        }
    }

    /** 판단에 쓰는 방 하나의 그때 모습 (복사본) */
    public record RoomView(int roomId, String name, Map<Metric, List<Sample>> history, Boolean occupied,
                           Instant occupiedSince, Instant envAt, Instant lastSignalAt, boolean auto) {
    }

    private static final class Room {
        final String name;
        final Map<Metric, MetricHistory> history = new EnumMap<>(Metric.class);
        Boolean occupied;
        Instant occupiedAt;
        Instant occupiedSince;
        Instant envAt;
        Instant lastSignalAt;
        boolean auto = true;

        Room(int id, ClimateProperties props) {
            this.name = props.rooms().get(id);
            for (Metric m : Metric.values()) {
                history.put(m, new MetricHistory(props.keep()));
            }
        }
    }
}
