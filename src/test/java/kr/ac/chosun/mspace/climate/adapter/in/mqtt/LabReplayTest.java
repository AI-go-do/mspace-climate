package kr.ac.chosun.mspace.climate.adapter.in.mqtt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.climate.MutableClock;
import kr.ac.chosun.mspace.climate.appliance.ClimateFacade;
import kr.ac.chosun.mspace.climate.application.AlertLog;
import kr.ac.chosun.mspace.climate.application.ClimateAgent;
import kr.ac.chosun.mspace.climate.application.ClimateProperties;
import kr.ac.chosun.mspace.climate.application.ClimateStore;
import kr.ac.chosun.mspace.climate.application.DecisionBook;
import kr.ac.chosun.mspace.climate.application.ReadingIntake;
import kr.ac.chosun.mspace.climate.application.RoomAnalyzer;
import kr.ac.chosun.mspace.climate.application.RoomDeriver;
import kr.ac.chosun.mspace.climate.application.SensorReading;
import kr.ac.chosun.mspace.climate.application.LabReplay;
import kr.ac.chosun.mspace.climate.domain.AnomalyDetector;
import kr.ac.chosun.mspace.climate.domain.BaselineCalculator;
import kr.ac.chosun.mspace.climate.domain.Metric;
import kr.ac.chosun.mspace.climate.domain.MetricRule;
import kr.ac.chosun.mspace.climate.domain.TrendForecaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 실습실 센서가 멈췄을 때의 녹화 재생 */
class LabReplayTest {

    MutableClock clock;
    ClimateStore store;
    ReadingIntake intake;
    List<String> pushed;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-12T07:00:00Z"));
        var props = new ClimateProperties(Map.of(1, "1번 방", 2, "2번 방", 3, "3번 방"), Duration.ofDays(7), 2.5, 30,
                Duration.ofMinutes(30), 5, Duration.ofMinutes(15), Duration.ofMinutes(20), Duration.ofMinutes(10),
                Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofMinutes(30),
                Map.of(Metric.CO2, new MetricRule(null, 1000.0, 150, 400, false, null),
                        Metric.TEMPERATURE, new MetricRule(18.0, 28.0, 1.5, 4, false, null),
                        Metric.HUMIDITY, new MetricRule(30.0, 70.0, 8, 20, false, null)),
                new ClimateProperties.Comfort(24, 26, 20, 22), List.of(), null, null, null);
        store = new ClimateStore(props, clock);
        var facade = new ClimateFacade(List.of(), props.holdManual(), 100, clock);
        var analyzer = new RoomAnalyzer(props, new BaselineCalculator(java.time.ZoneId.of("Asia/Seoul"), 2.5, 30),
                new TrendForecaster(Duration.ofMinutes(30), 5),
                new AnomalyDetector(Duration.ofMinutes(15), Duration.ofMinutes(20)), clock);
        var agent = new ClimateAgent(store, analyzer, facade, new AlertLog(props.alertEvery(), 100, a -> { }, clock),
                props, clock, new DecisionBook(50), false);
        var deriver = new RoomDeriver(1, Map.of(2, new RoomDeriver.Offset(0, 0, 40, 0, 0, 0, 10),
                3, new RoomDeriver.Offset(0, 0, -30, 0, 0, 0, 10)));
        pushed = new ArrayList<>();
        intake = new ReadingIntake(store, deriver, agent, (r, src) -> pushed.add(r.roomId() + ":" + src), clock);
    }

    static LabReplay replay(String... rows) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(("minute_utc,spot,co2,temperature,humidity\n" + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return LabReplay.load(new ByteArrayInputStream(out.toByteArray()));
    }

    @Test
    @DisplayName("지금과 같은 요일·시각의 기록(1주 전)을 지금 분 값으로 낸다")
    void sameTimeLastWeek() throws Exception {
        var r = replay("2026-11-05T06:58,앞쪽,850.0,23.1,41.0", "2026-11-05T07:00,앞쪽,900.0,23.4,42.0", "2026-11-08T02:30,앞쪽,600.0,20.0,40.0");
        List<SensorReading> out = r.at(Instant.parse("2026-11-12T07:00:30Z"), Map.of("앞쪽", 1));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).ppm()).isEqualTo(900);
        assertThat(out.get(0).roomId()).isEqualTo(1);
        assertThat(out.get(0).observedAt()).isEqualTo(Instant.parse("2026-11-12T07:00:00Z"));
    }

    @Test
    @DisplayName("그 분에 값이 없으면 10분 안의 앞 값, 더 멀면 내지 않는다")
    void gapFill() throws Exception {
        var r = replay("2026-11-05T06:52,앞쪽,850.0,23.1,41.0", "2026-11-08T02:30,앞쪽,600.0,20.0,40.0");
        assertThat(r.at(Instant.parse("2026-11-12T07:00:00Z"), Map.of("앞쪽", 1))).extracting(SensorReading::ppm).containsExactly(850);
        assertThat(r.at(Instant.parse("2026-11-12T07:05:00Z"), Map.of("앞쪽", 1))).isEmpty();
    }

    @Test
    @DisplayName("재생 값은 REPLAY로 보내고, 실제 값이 오면 10분 동안 재생 값을 버린다")
    void liveWins() {
        SensorReading rep = new SensorReading("replay-1-a", 1, "CO2", null, 800, 23.0, 40.0, Instant.parse("2026-11-12T07:00:00Z"));
        assertThat(intake.replay(rep, false)).isTrue();
        assertThat(pushed).contains("1:REPLAY");
        assertThat(intake.replayRooms()).contains(1);

        clock.advance(Duration.ofMinutes(1));
        intake.direct(new SensorReading("mqtt-1", 1, "CO2", null, 700, 22.0, 41.0, clock.instant()));
        assertThat(intake.replayRooms()).doesNotContain(1);
        clock.advance(Duration.ofMinutes(1));
        assertThat(intake.replay(new SensorReading("replay-1-b", 1, "CO2", null, 810, 23.0, 40.0, clock.instant()), false)).isFalse();

        clock.advance(Duration.ofMinutes(11));
        assertThat(intake.replay(new SensorReading("replay-1-c", 1, "CO2", null, 820, 23.0, 40.0, clock.instant()), false)).isTrue();
    }

    @Test
    @DisplayName("켜질 때 채우는 값(quiet)은 저장만 하고 화면에는 안 보낸다")
    void quietBackfill() {
        assertThat(intake.replay(new SensorReading("replay-1-q", 1, "CO2", null, 800, 23.0, 40.0, Instant.parse("2026-11-12T06:30:00Z")), true)).isTrue();
        assertThat(pushed).isEmpty();
    }
}
