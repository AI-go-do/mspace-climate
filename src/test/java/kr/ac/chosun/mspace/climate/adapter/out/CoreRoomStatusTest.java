package kr.ac.chosun.mspace.climate.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.chosun.mspace.climate.application.ClimateProperties;
import kr.ac.chosun.mspace.climate.application.ClimateStore;
import kr.ac.chosun.mspace.climate.domain.Metric;
import kr.ac.chosun.mspace.climate.domain.MetricRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** core 방 현황 → 재실. 가상 방은 건너뛴다 */
class CoreRoomStatusTest {

    @Test
    @DisplayName("가상 방(시뮬레이션 손님)은 core 재실을 받지 않고, 실제 방(1번)만 core 재실을 따른다")
    void skipsVirtualRooms() {
        // given
        Clock clock = Clock.fixed(Instant.parse("2026-11-12T06:00:00Z"), ZoneOffset.UTC);
        ClimateStore store = new ClimateStore(props(), clock);
        CoreRoomStatus status = new CoreRoomStatus("http://core", clock, Set.of(2));
        var body = JsonMapper.builder().build().readTree("""
                {"rooms":[{"roomId":1,"presence":"UNKNOWN","status":"IN_USE"},
                          {"roomId":2,"presence":"EMPTY","status":"AVAILABLE"}]}""");
        // when
        status.apply(body, store);
        // then
        assertThat(store.view(1).occupied()).isTrue();
        assertThat(store.view(2).occupied()).isNull();
    }

    /** 방 1·2만 있는 설정 */
    static ClimateProperties props() {
        return new ClimateProperties(Map.of(1, "1번 방", 2, "2번 방"), Duration.ofDays(7), 2.5, 30,
                Duration.ofMinutes(30), 5, Duration.ofMinutes(15), Duration.ofMinutes(20), Duration.ofMinutes(10),
                Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofMinutes(30),
                Map.of(Metric.CO2, new MetricRule(null, 1000.0, 150, 400, false, null)),
                new ClimateProperties.Comfort(24, 26, 20, 22), List.of(), null, null, null);
    }
}
