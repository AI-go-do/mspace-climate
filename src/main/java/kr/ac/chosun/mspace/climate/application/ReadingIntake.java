package kr.ac.chosun.mspace.climate.application;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 센서 값이 들어오는 하나의 입구. 게이트웨이 사본(mirrored)과 MQTT 직접 구독(direct)이 같은 길을 탄다.
 * 받으면 저장 → 열린 대시보드에 바로 보냄 → 그 방만 바로 판단. 실습실 값이면 센서 없는 방 값도 만든다.
 * MQTT로 직접 받는 방은 사본 공기 값을 버린다(같은 방에 두 출처가 섞이지 않게).
 */
public class ReadingIntake {

    /** 저장된 값 하나 (source: REAL/DERIVED) */
    public interface Listener {
        void stored(SensorReading r, String source);
    }

    private final ClimateStore store;
    private final RoomDeriver deriver;
    private final ClimateAgent agent;
    private final Listener listener;
    private final Set<Integer> direct = ConcurrentHashMap.newKeySet();

    public ReadingIntake(ClimateStore store, RoomDeriver deriver, ClimateAgent agent, Listener listener) {
        this.store = store;
        this.deriver = deriver == null ? new RoomDeriver(0, null) : deriver;
        this.agent = agent;
        this.listener = listener;
    }

    /** 게이트웨이 사본 (core가 장치 토큰을 확인하고 받아 준 것) */
    public boolean mirrored(SensorReading r) {
        if ("CO2".equals(r.kind()) && (direct.contains(r.roomId()) || deriver.derived(r.roomId()))) {
            return false;
        }
        return take(r);
    }

    /** MQTT로 이 방 센서 값을 직접 받음. 이 방은 이제 만든 값 대신 실제 값을 쓴다 */
    public boolean direct(SensorReading r) {
        direct.add(r.roomId());
        return take(r);
    }

    public Set<Integer> directRooms() {
        return Set.copyOf(direct);
    }

    private boolean take(SensorReading r) {
        if (!store.accept(r)) {
            return false;
        }
        listener.stored(r, "REAL");
        agent.tickRoom(r.roomId());
        Instant at = r.observedAt() == null ? Instant.now() : r.observedAt();
        for (SensorReading d : deriver.derive(r, at)) {
            if (!direct.contains(d.roomId()) && store.accept(d)) {
                listener.stored(d, "DERIVED");
                agent.tickRoom(d.roomId());
            }
        }
        return true;
    }
}
