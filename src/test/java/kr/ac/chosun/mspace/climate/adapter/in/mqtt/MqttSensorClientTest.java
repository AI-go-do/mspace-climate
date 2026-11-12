package kr.ac.chosun.mspace.climate.adapter.in.mqtt;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.climate.MutableClock;
import kr.ac.chosun.mspace.climate.appliance.ClimateFacade;
import kr.ac.chosun.mspace.climate.application.AlertLog;
import kr.ac.chosun.mspace.climate.application.BoardModel;
import kr.ac.chosun.mspace.climate.application.ClimateAgent;
import kr.ac.chosun.mspace.climate.application.ClimateProperties;
import kr.ac.chosun.mspace.climate.application.ClimateStore;
import kr.ac.chosun.mspace.climate.application.DecisionBook;
import kr.ac.chosun.mspace.climate.application.MqttProperties;
import kr.ac.chosun.mspace.climate.application.ReadingIntake;
import kr.ac.chosun.mspace.climate.application.RoomAnalyzer;
import kr.ac.chosun.mspace.climate.application.RoomDeriver;
import kr.ac.chosun.mspace.climate.application.SensorReading;
import kr.ac.chosun.mspace.climate.application.SensorRegistry;
import kr.ac.chosun.mspace.climate.domain.AnomalyDetector;
import kr.ac.chosun.mspace.climate.domain.BaselineCalculator;
import kr.ac.chosun.mspace.climate.domain.Metric;
import kr.ac.chosun.mspace.climate.domain.MetricRule;
import kr.ac.chosun.mspace.climate.domain.TrendForecaster;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 브로커 없이 받은 메시지를 바로 넣어 본다 (연결은 Paho 몫) */
class MqttSensorClientTest {

    static final String TOPIC = "application/app/device/a0a0a0a0a0000001/event/up";

    MutableClock clock;
    ClimateStore store;
    SensorRegistry sensors;
    BoardModel model;
    ReadingIntake intake;
    MqttSensorClient client;
    List<String> pushed;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-12T07:00:00Z"));
        var props = new ClimateProperties(Map.of(1, "1번 방", 2, "2번 방", 3, "3번 방"), Duration.ofDays(7), 2.5, 30,
                Duration.ofMinutes(30), 5, Duration.ofMinutes(15), Duration.ofMinutes(20), Duration.ofMinutes(10),
                Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofMinutes(30),
                Map.of(Metric.CO2, new MetricRule(null, 1000.0, 150, 400),
                        Metric.TEMPERATURE, new MetricRule(18.0, 28.0, 1.5, 4),
                        Metric.HUMIDITY, new MetricRule(30.0, 70.0, 8, 20)),
                new ClimateProperties.Comfort(24, 26, 20, 22), List.of(), null, null);
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
        intake = new ReadingIntake(store, deriver, agent, (r, src) -> pushed.add(r.roomId() + ":" + src));
        sensors = new SensorRegistry(List.of(2, 3), Map.of("앞쪽", 1));
        model = new BoardModel();
        var mp = new MqttProperties(true, "tcp://127.0.0.1:1", null, null, "test", "application/+/device/+/event/up",
                "실습실", Map.of("앞쪽", 1), List.of(2, 3), "aip/team5/jaeung/inference/+/state");
        client = new MqttSensorClient(mp, intake, sensors, model, clock);
    }

    static MqttMessage msg(String json) {
        return new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
    }

    static String raw(String dev, String location, String spot, int fcnt, int co2) {
        return """
                {"deviceInfo":{"deviceProfileName":"AM107","devEui":"%s","tags":{"location":"%s","spot":"%s"}},
                 "fCnt":%d,"object":{"co2":%d,"temperature":24.3,"humidity":41.5,"battery":90},
                 "rxInfo":[{"nsTime":"2026-11-12T06:59:50.5+00:00"},{"nsTime":"2026-11-12T06:59:49.1+00:00"}]}"""
                .formatted(dev, location, spot, fcnt, co2);
    }

    @Test
    @DisplayName("실습실 센서 값이 오면 그 자리의 방에 실제 값으로 들어가고, 시각은 가장 이른 nsTime의 1분 칸")
    void rawToRoom() {
        client.messageArrived(TOPIC, msg(raw("a0a0a0a0a0000001", "실습실", "앞쪽", 10, 812)));

        var h = store.view(1).history().get(Metric.CO2);
        assertThat(h).hasSize(1);
        assertThat(h.get(0).value()).isEqualTo(812);
        assertThat(h.get(0).at()).isEqualTo(Instant.parse("2026-11-12T06:59:00Z")); // 1분 칸
        assertThat(pushed).contains("1:REAL", "2:DERIVED", "3:DERIVED");
        assertThat(sensors.list()).singleElement().satisfies(s -> {
            assertThat(s.roomId()).isEqualTo(1);
            assertThat(s.co2()).isEqualTo(812);
        });
    }

    @Test
    @DisplayName("설정에 없는 자리는 빈 방에 붙고, 그 방은 만든 값 대신 실제 값을 쓴다. 다른 공간 센서는 버린다")
    void autoAssignAndSkipOthers() {
        client.messageArrived(TOPIC, msg(raw("d2", "실습실", "뒤쪽", 1, 700)));
        client.messageArrived(TOPIC, msg(raw("d3", "사무실", "책상", 1, 900)));
        client.messageArrived(TOPIC, msg(raw("d1", "실습실", "앞쪽", 1, 800)));

        assertThat(sensors.list()).extracting(SensorRegistry.Sensor::roomId).containsExactly(1, 2);
        assertThat(intake.directRooms()).containsExactlyInAnyOrder(1, 2);
        // 2번 방은 실제 센서 값 하나만 (1번 방에서 만든 값은 안 섞임)
        assertThat(store.view(2).history().get(Metric.CO2)).extracting(s -> s.value()).containsExactly(700.0);
        assertThat(store.view(3).history().get(Metric.CO2)).extracting(s -> s.value()).containsExactly(770.0);
    }

    @Test
    @DisplayName("온습도만 재는 처음 보는 센서는 방을 차지하지 않는다 (목록에만)")
    void tempOnlySensorNotAssigned() {
        client.messageArrived(TOPIC, msg("""
                {"deviceInfo":{"devEui":"t1","tags":{"location":"실습실","spot":"중앙 우측"}},"fCnt":1,
                 "object":{"temperature":23.1,"humidity":40}}"""));
        client.messageArrived(TOPIC, msg(raw("d2", "실습실", "뒤쪽", 1, 700)));

        assertThat(sensors.list()).extracting(SensorRegistry.Sensor::id, SensorRegistry.Sensor::roomId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("d2", 2), org.assertj.core.groups.Tuple.tuple("t1", null));
        assertThat(intake.directRooms()).containsExactly(2);
    }

    @Test
    @DisplayName("MQTT로 받는 방은 게이트웨이 사본 공기 값을 버린다 (재실 이벤트는 받음)")
    void mirroredIgnoredForDirectRoom() {
        client.messageArrived(TOPIC, msg(raw("d1", "실습실", "앞쪽", 1, 800)));

        assertThat(intake.mirrored(new SensorReading("m1", 1, "CO2", null, 650, 24.0, 40.0, clock.instant()))).isFalse();
        assertThat(intake.mirrored(new SensorReading("m2", 1, "OCCUPANCY", true, null, null, null, clock.instant())))
                .isTrue();
    }

    @Test
    @DisplayName("같은 메시지가 두 번 와도 한 번만, 모양이 다른 본문은 조용히 버린다")
    void duplicateAndGarbage() {
        client.messageArrived(TOPIC, msg(raw("d1", "실습실", "앞쪽", 5, 800)));
        client.messageArrived(TOPIC, msg(raw("d1", "실습실", "앞쪽", 5, 800)));
        client.messageArrived(TOPIC, msg("not json"));
        client.messageArrived(TOPIC, msg("{\"object\":{\"co2\":1}}"));

        assertThat(store.view(1).history().get(Metric.CO2)).hasSize(1);
    }

    @Test
    @DisplayName("보드 A 모델 결과(공간 객체)를 읽어 공간별로 둔다. 15분 지나면 안 쓴다")
    void inference() {
        client.messageArrived("aip/team5/jaeung/inference/lab/state", msg("""
                {"space":"실습실","slug":"lab","slot":"2026-11-12T06:55:00Z",
                 "forecast":{"co2":{"now":850.0,"points":[{"minutes":5,"value":868.2},{"minutes":60,"value":1012.5}]},
                             "temperature":{"now":23.0,"points":[{"minutes":30,"value":23.4}]}},
                 "anomaly":{"score":0.95,"threshold":2.31,"is_anomaly":false,"cause":null},
                 "state":{"occupied":true,"occupied_probability":0.9,"ventilation_probability":0.8}}"""));

        var s = model.fresh("lab", clock.instant()).orElseThrow();
        assertThat(s.co2Now()).isEqualTo(850.0);
        assertThat(s.co2()).extracting(BoardModel.Point::minutes).containsExactly(5, 60);
        assertThat(s.anomalyThreshold()).isEqualTo(2.31);
        assertThat(s.occupiedProbability()).isEqualTo(0.9);
        clock.advance(Duration.ofMinutes(16));
        assertThat(model.fresh("lab", clock.instant())).isEmpty();
    }
}
