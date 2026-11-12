package kr.ac.chosun.mspace.climate.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 센서 값이 들어오는 하나의 입구. 게이트웨이 사본(mirrored)과 MQTT 직접 구독(direct)이 같은 길을 탄다.
 * 받으면 저장 → 열린 대시보드에 바로 보냄 → 그 방만 바로 판단. 실습실 값이면 센서 없는 방 값도 만든다.
 * MQTT로 직접 받는 방은 사본 공기 값을 버린다(같은 방에 두 출처가 섞이지 않게).
 * 실습실 센서가 멈췄을 때는 녹화 재생(replay)이 같은 길로 들어온다. 실제 값이 최근 LIVE_GRACE 안에 왔으면 재생 값은 버린다.
 */
public class ReadingIntake {

    /** 실제 값이 이만큼 안 오면 녹화 재생으로 채운다 */
    public static final Duration LIVE_GRACE = Duration.ofMinutes(10);

    /** 저장된 값 하나 (source: REAL/DERIVED/REPLAY) */
    public interface Listener {
        void stored(SensorReading r, String source);
    }

    private final ClimateStore store;
    private final RoomDeriver deriver;
    private final ClimateAgent agent;
    private final Listener listener;
    private final Set<Integer> direct = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Instant> lastLive = new ConcurrentHashMap<>();
    private final Set<Integer> replayed = ConcurrentHashMap.newKeySet();
    private final Clock clock;

    public ReadingIntake(ClimateStore store, RoomDeriver deriver, ClimateAgent agent, Listener listener) {
        this(store, deriver, agent, listener, Clock.systemUTC());
    }

    public ReadingIntake(ClimateStore store, RoomDeriver deriver, ClimateAgent agent, Listener listener, Clock clock) {
        this.clock = clock;
        this.store = store;
        this.deriver = deriver == null ? new RoomDeriver(0, null) : deriver;
        this.agent = agent;
        this.listener = listener;
    }

    /** 게이트웨이 사본 (core가 장치 토큰을 확인하고 받아 준 것) */
    public boolean mirrored(SensorReading r) {
        if ("CO2".equals(r.kind()) && (direct.contains(r.roomId()) || replayed.contains(r.roomId())
                || deriver.derived(r.roomId()))) {
            return false;
        }
        return take(r, "REAL", true);
    }

    /** MQTT로 이 방 센서 값을 직접 받음. 이 방은 이제 만든 값·재생 값 대신 실제 값을 쓴다 */
    public boolean direct(SensorReading r) {
        direct.add(r.roomId());
        lastLive.put(r.roomId(), clock.instant());
        replayed.remove(r.roomId());
        return take(r, "REAL", true);
    }

    /**
     * 녹화 재생 값. 이 방에 실제 값이 최근에 왔으면 버린다.
     * quiet=true(시작할 때 지난 두 시간 채우기)면 저장만 하고 화면·판단에는 안 보낸다
     */
    public boolean replay(SensorReading r, boolean quiet) {
        if (liveRecently(r.roomId())) {
            return false;
        }
        replayed.add(r.roomId());
        return take(r, "REPLAY", !quiet);
    }

    public Set<Integer> directRooms() {
        return Set.copyOf(direct);
    }

    /** 지금 녹화 재생으로 채우는 방 */
    public Set<Integer> replayRooms() {
        return Set.copyOf(replayed);
    }

    private boolean liveRecently(int roomId) {
        Instant at = lastLive.get(roomId);
        return at != null && at.isAfter(clock.instant().minus(LIVE_GRACE));
    }

    private boolean take(SensorReading r, String source, boolean loud) {
        if (!store.accept(r)) {
            return false;
        }
        if (loud) {
            listener.stored(r, source);
            agent.tickRoom(r.roomId());
        }
        Instant at = r.observedAt() == null ? clock.instant() : r.observedAt();
        for (SensorReading d : deriver.derive(r, at)) {
            if (!direct.contains(d.roomId()) && !replayed.contains(d.roomId()) && store.accept(d)) {
                if (loud) {
                    listener.stored(d, "DERIVED");
                    agent.tickRoom(d.roomId());
                }
            }
        }
        return true;
    }
}
