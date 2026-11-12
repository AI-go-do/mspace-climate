package kr.ac.chosun.mspace.climate.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoomDeriverTest {

    final RoomDeriver deriver = new RoomDeriver(1, Map.of(
            2, new RoomDeriver.Offset(0.6, -3, 40, 0.3, 1.5, 25, 47),
            5, new RoomDeriver.Offset(0.9, -2, 80, 0.4, 1.5, 40, 53)));

    @Test
    @DisplayName("1번 방 실제 값 하나로 다른 방 값을 만들고, 차이는 고정 차이 + 흔들림 안쪽이다")
    void closeToReal() {
        Instant at = Instant.parse("2026-11-12T06:00:00Z");
        for (int m = 0; m < 120; m++) {
            List<SensorReading> out = deriver.derive(
                    new SensorReading("e" + m, 1, "CO2", null, 700, 24.0, 45.0, at), at.plus(Duration.ofMinutes(m)));
            assertThat(out).hasSize(2);
            for (SensorReading r : out) {
                var o = deriver.rooms().get(r.roomId());
                assertThat(Math.abs(r.ppm() - (700 + o.co2()))).isLessThanOrEqualTo((int) Math.ceil(o.co2Wobble()));
                assertThat(Math.abs(r.temperatureC() - (24.0 + o.temp()))).isLessThanOrEqualTo(o.tempWobble() + 0.05);
                assertThat(Math.abs(r.humidityPct() - (45.0 + o.humidity()))).isLessThanOrEqualTo(o.humidityWobble() + 0.05);
                assertThat(r.eventId()).isEqualTo("e" + m + "-r" + r.roomId());
            }
        }
    }

    @Test
    @DisplayName("실제 값이 오르면 만든 방 값도 같이 오른다")
    void followsReal() {
        Instant at = Instant.parse("2026-11-12T06:00:00Z");
        int low = deriver.derive(new SensorReading("a", 1, "CO2", null, 600, 24.0, 45.0, at), at).get(0).ppm();
        int high = deriver.derive(new SensorReading("b", 1, "CO2", null, 900, 24.0, 45.0, at), at).get(0).ppm();
        assertThat(high - low).isEqualTo(300);
    }

    @Test
    @DisplayName("다른 방 값이나 재실 이벤트로는 만들지 않는다")
    void onlyFromSourceCo2() {
        Instant at = Instant.parse("2026-11-12T06:00:00Z");
        assertThat(deriver.derive(new SensorReading("a", 2, "CO2", null, 600, 24.0, 45.0, at), at)).isEmpty();
        assertThat(deriver.derive(new SensorReading("a", 1, "OCCUPANCY", true, null, null, null, at), at)).isEmpty();
    }
}
