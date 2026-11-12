package kr.ac.chosun.mspace.climate.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 방 공기 시뮬레이션: 받은 재실(core)·기기가 값에 반영되는지 */
class RoomSimulatorTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Instant T0 = Instant.parse("2026-11-12T06:00:00Z"); // 15:00 KST
    static final Map<Integer, Boolean> EMPTY = Map.of(1, false);
    static final Map<Integer, Boolean> IN = Map.of(1, true);

    @Test
    @DisplayName("빈 방 값은 실습실 밤 수준(CO2 500~650, 온도 20~23°C)에 머문다")
    void emptyRoomStaysNearLab() {
        // given
        RoomSimulator sim = new RoomSimulator(Map.of(1, 4), 1e9, SEOUL, 1);
        sim.start(T0);
        // when: 빈 방으로 60분
        Instant t = T0;
        for (int i = 0; i < 60; i++) {
            t = t.plusSeconds(60);
            sim.step(t, 1, Map.of(), EMPTY);
        }
        // then
        var s = sim.read().get(0);
        assertThat(s.co2()).isBetween(500.0, 650.0);
        assertThat(s.temperatureC()).isBetween(20.0, 23.0);
        assertThat(s.occupied()).isFalse();
    }

    @Test
    @DisplayName("재실은 시뮬레이션이 만들지 않는다: 모르거나 빈 방이면 몇 시간이 지나도 값이 오르지 않는다")
    void neverInventsPeople() {
        // given: 상황도 자주(1분마다) 만들게
        RoomSimulator sim = new RoomSimulator(Map.of(1, 4, 2, 4), 1, SEOUL, 11);
        sim.start(T0);
        // when: 재실을 모름(빈 맵)으로 3시간
        Instant t = T0;
        for (int i = 0; i < 180; i++) {
            t = t.plusSeconds(60);
            sim.step(t, 1, Map.of(), Map.of());
        }
        // then: 모든 방이 빈 방 수준, 상황도 안 생김
        for (var s : sim.read()) {
            assertThat(s.occupied()).isFalse();
            assertThat(s.co2()).isLessThan(650.0);
            assertThat(s.event()).isNull();
        }
    }

    @Test
    @DisplayName("실제로 입실한 방(core 재실 true)만 CO2가 1000ppm 가까이 오르고, 나가면 내려간다")
    void realCheckInRaisesCo2() {
        // given
        RoomSimulator sim = new RoomSimulator(Map.of(1, 4), 1e9, SEOUL, 5);
        sim.start(T0);
        Instant t = T0;
        // when: 입실 90분
        for (int i = 0; i < 90; i++) {
            t = t.plusSeconds(60);
            sim.step(t, 1, Map.of(), IN);
        }
        var in = sim.read().get(0);
        // then
        assertThat(in.occupied()).isTrue();
        assertThat(in.co2()).isGreaterThan(950.0);
        // when: 퇴실 60분
        for (int i = 0; i < 60; i++) {
            t = t.plusSeconds(60);
            sim.step(t, 1, Map.of(), EMPTY);
        }
        // then
        assertThat(sim.read().get(0).co2()).isLessThan(in.co2() - 200);
    }

    @Test
    @DisplayName("온도 상승 상황은 사람이 있는 방에만 생긴다")
    void heatEventOnlyInOccupiedRoom() {
        // given: 1번 방만 입실, 상황은 1분마다
        RoomSimulator sim = new RoomSimulator(Map.of(1, 4, 2, 4), 1, SEOUL, 9);
        sim.start(T0);
        Instant t = T0;
        // when
        for (int i = 0; i < 5; i++) {
            t = t.plusSeconds(60);
            sim.step(t, 1, Map.of(), Map.of(1, true, 2, false));
        }
        // then
        var rooms = sim.read();
        assertThat(rooms.get(0).event()).isEqualTo("HEAT");
        assertThat(rooms.get(1).event()).isNull();
    }

    @Test
    @DisplayName("사람이 있으면 CO2가 오르고, 환풍기를 켜면 내려간다")
    void ventilationLowersCo2() {
        // given: 입실한 방
        RoomSimulator sim = new RoomSimulator(Map.of(1, 4), 1e9, SEOUL, 7);
        sim.start(T0);
        Instant t = T0;
        double before = sim.read().get(0).co2();
        for (int i = 0; i < 20; i++) {
            t = t.plusSeconds(60);
            sim.step(t, 1, Map.of(), IN);
        }
        double crowded = sim.read().get(0).co2();
        assertThat(crowded).isGreaterThan(before + 80);
        // when: 환풍기 켬 20분
        var vent = Map.of(1, new RoomSimulator.Devices(false, false, false, null, true));
        for (int i = 0; i < 20; i++) {
            t = t.plusSeconds(60);
            sim.step(t, 1, vent, IN);
        }
        // then
        assertThat(sim.read().get(0).co2()).isLessThan(crowded - 80);
    }

    @Test
    @DisplayName("에어컨 냉방을 켜면 온도가 목표 쪽으로 내려간다")
    void coolingLowersTemperature() {
        // given: 같은 시작 두 개, 하나만 냉방 22도
        RoomSimulator off = new RoomSimulator(Map.of(1, 4), 1e9, SEOUL, 3);
        RoomSimulator on = new RoomSimulator(Map.of(1, 4), 1e9, SEOUL, 3);
        off.start(T0);
        on.start(T0);
        var cool = Map.of(1, new RoomSimulator.Devices(true, false, false, 20, false));
        Instant t = T0;
        // when: 30분
        for (int i = 0; i < 30; i++) {
            t = t.plusSeconds(60);
            off.step(t, 1, Map.of(), IN);
            on.step(t, 1, cool, IN);
        }
        // then
        assertThat(on.read().get(0).temperatureC()).isLessThan(off.read().get(0).temperatureC() - 0.5);
    }

    @Test
    @DisplayName("가상 방(2번)은 시뮬레이션이 손님을 만든다: 6시간 동안 여러 번 들어오고 나가며, 인원은 방 크기 안, 실제 방(1번)은 core 재실만 따른다")
    void virtualRoomsHaveVisitors() {
        // given: 1번 실제, 2번 가상(3~5명), 빈 틈 10~40분·머무름 50~120분
        var visitors = new RoomSimulator.Visitors(Map.of(2, new int[] {3, 5}), 10, 40, 50, 120);
        RoomSimulator sim = new RoomSimulator(Map.of(1, 4, 2, 4), 1e9, SEOUL, 21, visitors);
        sim.start(T0);
        Instant t = T0;
        int checkIns = 0;
        boolean was = sim.read().get(1).occupied();
        double peak = 0;
        // when: core 재실은 계속 "빈 방"으로 6시간
        for (int i = 0; i < 360; i++) {
            t = t.plusSeconds(60);
            sim.step(t, 1, Map.of(), Map.of(1, false, 2, false));
            var room1 = sim.read().get(0);
            var room2 = sim.read().get(1);
            // then: 1번은 늘 빈 방, 인원·퇴실 시각 없음
            assertThat(room1.occupied()).isFalse();
            assertThat(room1.people()).isNull();
            if (room2.occupied()) {
                assertThat(room2.people()).isBetween(3, 5);
                assertThat(room2.until()).isAfter(t);
                peak = Math.max(peak, room2.co2());
                if (!was) {
                    checkIns++;
                }
            } else {
                assertThat(room2.people()).isNull();
            }
            was = room2.occupied();
        }
        // then: 여러 번 들어왔고, 사람이 있을 때 CO2가 실제로 올랐다
        assertThat(checkIns).isBetween(2, 6);
        assertThat(peak).isGreaterThan(800.0);
        assertThat(sim.isVirtual(2)).isTrue();
        assertThat(sim.isVirtual(1)).isFalse();
    }
}
