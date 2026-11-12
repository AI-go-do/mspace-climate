package kr.ac.chosun.mspace.climate.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 방 공기 시뮬레이션: 사람·기기가 값에 반영되는지 */
class RoomSimulatorTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Instant T0 = Instant.parse("2026-11-12T06:00:00Z"); // 15:00 KST

    @Test
    @DisplayName("빈 방 값은 실습실 밤 수준(CO2 500~650, 온도 20~23°C)에 머문다")
    void emptyRoomStaysNearLab() {
        // given: 사람이 들어올 틈이 없게(인원 0명 방만) 시작
        RoomSimulator sim = new RoomSimulator(Map.of(1, 5), 1e9, SEOUL, 1);
        sim.start(T0);
        // when: 사람 없을 때 5분
        sim.step(T0.plusSeconds(60), 1, Map.of());
        // then
        var s = sim.read().get(0);
        assertThat(s.co2()).isBetween(500.0, 650.0);
        assertThat(s.temperatureC()).isBetween(20.0, 23.0);
    }

    @Test
    @DisplayName("사람이 있으면 CO2가 오르고, 환풍기를 켜면 내려간다")
    void ventilationLowersCo2() {
        // given: 한 시간 흘려 사람이 들어온 상태를 만든다
        RoomSimulator sim = new RoomSimulator(Map.of(1, 5), 1e9, SEOUL, 7);
        sim.start(T0);
        Instant t = T0;
        int guard = 0;
        while (sim.read().get(0).people() == 0 && guard++ < 200) {
            t = t.plusSeconds(60);
            sim.step(t, 1, Map.of());
        }
        double before = sim.read().get(0).co2();
        for (int i = 0; i < 20; i++) {
            t = t.plusSeconds(60);
            sim.step(t, 1, Map.of());
        }
        double crowded = sim.read().get(0).co2();
        assertThat(crowded).isGreaterThan(before + 80);
        // when: 환풍기 켬 20분
        var vent = Map.of(1, new RoomSimulator.Devices(false, false, false, null, true));
        for (int i = 0; i < 20; i++) {
            t = t.plusSeconds(60);
            sim.step(t, 1, vent);
        }
        // then
        assertThat(sim.read().get(0).co2()).isLessThan(crowded - 80);
    }

    @Test
    @DisplayName("에어컨 냉방을 켜면 온도가 목표 쪽으로 내려간다")
    void coolingLowersTemperature() {
        // given: 같은 시작 두 개, 하나만 냉방 22도
        RoomSimulator off = new RoomSimulator(Map.of(1, 5), 1e9, SEOUL, 3);
        RoomSimulator on = new RoomSimulator(Map.of(1, 5), 1e9, SEOUL, 3);
        off.start(T0);
        on.start(T0);
        var cool = Map.of(1, new RoomSimulator.Devices(true, false, false, 20, false));
        Instant t = T0;
        // when: 30분
        for (int i = 0; i < 30; i++) {
            t = t.plusSeconds(60);
            off.step(t, 1, Map.of());
            on.step(t, 1, cool);
        }
        // then
        assertThat(on.read().get(0).temperatureC()).isLessThan(off.read().get(0).temperatureC() - 0.5);
    }
}
