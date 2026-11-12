package kr.ac.chosun.mspace.climate.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.climate.MutableClock;
import kr.ac.chosun.mspace.climate.appliance.ApplianceCommand;
import kr.ac.chosun.mspace.climate.appliance.ApplianceKind;
import kr.ac.chosun.mspace.climate.appliance.ApplianceState;
import kr.ac.chosun.mspace.climate.appliance.ClimateFacade;
import kr.ac.chosun.mspace.climate.appliance.VirtualAppliance;
import kr.ac.chosun.mspace.climate.domain.AnomalyDetector;
import kr.ac.chosun.mspace.climate.domain.BaselineCalculator;
import kr.ac.chosun.mspace.climate.domain.Metric;
import kr.ac.chosun.mspace.climate.domain.MetricRule;
import kr.ac.chosun.mspace.climate.domain.TrendForecaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClimateAgentTest {

    static final Instant START = Instant.parse("2026-11-12T06:00:00Z");

    MutableClock clock;
    ClimateProperties props;
    ClimateStore store;
    ClimateFacade facade;
    AlertLog alerts;
    ClimateAgent agent;
    DecisionBook decisions;
    List<Decision> pushed;
    List<Alert> sent;
    int seq;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        props = new ClimateProperties(Map.of(1, "1번 방"), Duration.ofDays(7), 2.5, 30, Duration.ofMinutes(30), 5,
                Duration.ofMinutes(15), Duration.ofMinutes(20), Duration.ofMinutes(10), Duration.ofMinutes(10),
                Duration.ofMinutes(30), Duration.ofMinutes(30),
                Map.of(Metric.CO2, new MetricRule(null, 1000.0, 150, 400),
                        Metric.TEMPERATURE, new MetricRule(18.0, 28.0, 1.5, 4),
                        Metric.HUMIDITY, new MetricRule(30.0, 70.0, 8, 20)),
                new ClimateProperties.Comfort(24, 26, 20, 22), List.of(), null, null);
        store = new ClimateStore(props, clock);
        facade = new ClimateFacade(List.of(new VirtualAppliance("ac-1", 1, ApplianceKind.AIRCON),
                new VirtualAppliance("purifier-1", 1, ApplianceKind.PURIFIER)), props.holdManual(), 100, clock);
        sent = new ArrayList<>();
        alerts = new AlertLog(props.alertEvery(), 100, sent::add, clock);
        RoomAnalyzer analyzer = new RoomAnalyzer(props, new BaselineCalculator(ZoneId.of("Asia/Seoul"), 2.5, 30),
                new TrendForecaster(Duration.ofMinutes(30), 5),
                new AnomalyDetector(Duration.ofMinutes(15), Duration.ofMinutes(20)), clock);
        decisions = new DecisionBook(50);
        pushed = new ArrayList<>();
        decisions.listen(pushed::add);
        agent = new ClimateAgent(store, analyzer, facade, alerts, props, clock, decisions, false);
    }

    /** 1분마다 값 하나 넣고 시계를 1분 돌린다 */
    void feed(int minutes, double temp, double tempPerMin, int ppm, int ppmPerMin) {
        for (int i = 0; i < minutes; i++) {
            store.accept(new SensorReading("e" + (seq++), 1, "CO2", null, ppm + ppmPerMin * i, temp + tempPerMin * i,
                    45.0 + (i % 2), clock.instant()));
            clock.advance(Duration.ofMinutes(1));
        }
        clock.advance(Duration.ofSeconds(-30));
    }

    ApplianceState ac() {
        return facade.states(1).stream().filter(s -> s.kind() == ApplianceKind.AIRCON).findFirst().orElseThrow();
    }

    ApplianceState purifier() {
        return facade.states(1).stream().filter(s -> s.kind() == ApplianceKind.PURIFIER).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("사람이 있고 온도가 기준에 닿기 전에 오르는 중이면 미리 냉방을 켠다 (목표 = 이 방 평소 온도)")
    void preCool() {
        // given: 1시간 평소 24도(살짝 흔들림) → 그다음 20분 동안 0.1도씩 올라 26도 근처
        store.setOccupancy(1, true, clock.instant());
        for (int i = 0; i < 60; i++) {
            feed(1, 24.0 + (i % 2) * 0.2, 0, 600 + (i % 3) * 5, 0);
            clock.advance(Duration.ofSeconds(30));
        }
        feed(20, 24.2, 0.08, 600, 0);

        // when
        agent.tick();

        // then
        assertThat(ac().power()).isEqualTo(ApplianceCommand.Power.ON);
        assertThat(ac().mode()).isEqualTo(ApplianceCommand.Mode.COOL);
        assertThat(ac().targetC()).isEqualTo(24);
        assertThat(facade.recentActions(1).get(0).reason()).contains("분 뒤").contains("냉방 24도");
        assertThat(sent).anyMatch(a -> a.audience() == Alert.Audience.ROOM && a.metric() == Metric.TEMPERATURE);
    }

    @Test
    @DisplayName("재실을 모르면(UNKNOWN) 덥더라도 켜지 않고, 관리자 화면 알림만 남긴다")
    void unknownOccupancyNoOn() {
        feed(30, 27.5, 0.0, 600, 0);   // 학습 중: 안전 한계 28도 근처
        feed(10, 28.1, 0.0, 600, 0);
        agent.tick();
        assertThat(ac().power()).isEqualTo(ApplianceCommand.Power.OFF);
        assertThat(sent).allMatch(a -> a.audience() == Alert.Audience.ADMIN);
    }

    @Test
    @DisplayName("빈 방이 10분 넘게 이어지면 켜져 있던 기기를 끈다")
    void emptyRoomOff() {
        facade.cool(1, 25, "앞서 켬");
        store.setOccupancy(1, false, clock.instant());
        feed(11, 24, 0, 600, 0);
        agent.tick();
        assertThat(ac().power()).isEqualTo(ApplianceCommand.Power.OFF);
        assertThat(facade.recentActions(1).get(0).reason()).contains("빈 방");
    }

    @Test
    @DisplayName("CO2가 곧 1000ppm에 닿으면 방 사용자에게 환기 알림 + 공기청정기, 30분 안에는 알림을 다시 안 보낸다")
    void co2Soon() {
        store.setOccupancy(1, true, clock.instant());
        feed(20, 24, 0, 680, 10);   // 870ppm, 1분 10ppm → 13분 뒤
        agent.tick();
        clock.advance(Duration.ofMinutes(1));
        agent.tick();

        assertThat(purifier().power()).isEqualTo(ApplianceCommand.Power.ON);
        assertThat(sent.stream().filter(a -> a.metric() == Metric.CO2)).hasSize(1);
        assertThat(sent.stream().filter(a -> a.metric() == Metric.CO2).findFirst().orElseThrow().message())
                .contains("환기");
    }

    @Test
    @DisplayName("자동 제어를 끈 방은 알림만 하고 기기는 안 건드린다")
    void autoOff() {
        store.setAuto(1, false);
        store.setOccupancy(1, true, clock.instant());
        feed(20, 24, 0, 680, 10);
        agent.tick();
        assertThat(purifier().power()).isEqualTo(ApplianceCommand.Power.OFF);
        assertThat(sent).isNotEmpty();
    }

    @Test
    @DisplayName("냉방으로 이 방 평소 온도까지 내려오면 끈다")
    void coolOffAtMean() {
        store.setOccupancy(1, true, clock.instant());
        for (int i = 0; i < 60; i++) {
            feed(1, 24.0 + (i % 2) * 0.2, 0, 600 + (i % 3) * 5, 0);
            clock.advance(Duration.ofSeconds(30));
        }
        facade.cool(1, 24, "앞서 켬");
        feed(10, 24.0, 0, 600, 0);
        agent.tick();
        assertThat(ac().power()).isEqualTo(ApplianceCommand.Power.OFF);
        assertThat(facade.recentActions(1).get(0).reason()).contains("평소");
    }

    @Test
    @DisplayName("공기 센서 값이 10분 넘게 끊기면 기기를 켜지 않고 끊김을 알린다")
    void offline() {
        store.setOccupancy(1, true, clock.instant());
        feed(20, 24, 0, 680, 15);
        clock.advance(Duration.ofMinutes(11));
        agent.tick();
        assertThat(purifier().power()).isEqualTo(ApplianceCommand.Power.OFF);
        assertThat(sent).anyMatch(a -> a.message().contains("안 들어와요"));
    }

    Decision co2Card() {
        return decisions.recent(50).stream().filter(d -> d.metric() == Metric.CO2 && "HIGH".equals(d.group()))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("판단 카드: 이미 넘은 값이면 예측 단계는 탐지 문장을 되풀이하지 않고 30분 뒤 값과 방향을 말한다")
    void predictAfterOver() {
        store.setOccupancy(1, true, clock.instant());
        feed(40, 24, 0, 900, 10);

        agent.tick();

        Decision d = co2Card();
        String predict = d.step(Decision.Step.PREDICT).text();
        assertThat(predict).startsWith("그냥 두면 ").contains("분 뒤 ").endsWith("더 오를 것 같아요");
        assertThat(predict).doesNotContain("넘었어요");
    }

    @Test
    @DisplayName("판단 카드: CO2가 곧 넘을 것 같으면 탐지·예측·알림·제어가 끝나고 다시 확인은 돌아가는 중으로 남는다")
    void decisionCardOpens() {
        store.setOccupancy(1, true, clock.instant());
        feed(20, 24, 0, 680, 10);

        agent.tick();

        Decision d = co2Card();
        assertThat(d.open()).isTrue();
        assertThat(d.step(Decision.Step.DETECT).status()).isEqualTo(Decision.Status.DONE);
        assertThat(d.step(Decision.Step.DETECT).text()).startsWith("CO2 870ppm");
        assertThat(d.step(Decision.Step.PREDICT).text()).contains("분 뒤");
        assertThat(d.step(Decision.Step.NOTIFY).status()).isEqualTo(Decision.Status.DONE);
        assertThat(d.step(Decision.Step.NOTIFY).text()).contains("알림함 연결 전");
        assertThat(d.step(Decision.Step.CONTROL).status()).isEqualTo(Decision.Status.DONE);
        assertThat(d.step(Decision.Step.CONTROL).text()).contains("공기청정기 켬");
        assertThat(d.step(Decision.Step.VERIFY).status()).isEqualTo(Decision.Status.RUNNING);
        assertThat(pushed).isNotEmpty();
    }

    @Test
    @DisplayName("판단 카드: 같은 일로 다시 판단해도 카드는 하나, 바뀐 게 없으면 화면에 다시 안 보낸다")
    void decisionCardNoDuplicate() {
        store.setOccupancy(1, true, clock.instant());
        feed(20, 24, 0, 680, 10);
        agent.tick();
        int before = pushed.size();

        agent.tick();

        assertThat(decisions.recent(50).stream().filter(d -> d.metric() == Metric.CO2)).hasSize(1);
        assertThat(pushed).hasSize(before);
    }

    @Test
    @DisplayName("판단 카드: 값이 평소로 돌아오면 다시 확인이 끝나고(체크) 카드가 닫힌다")
    void decisionCardCloses() {
        store.setOccupancy(1, true, clock.instant());
        feed(20, 24, 0, 680, 10);
        agent.tick();

        feed(25, 24, 0, 600, 0);
        agent.tick();

        Decision d = co2Card();
        assertThat(d.open()).isFalse();
        assertThat(d.step(Decision.Step.VERIFY).status()).isEqualTo(Decision.Status.DONE);
        assertThat(d.step(Decision.Step.VERIFY).text()).contains("평소 범위로 돌아왔어요");
    }

    @Test
    @DisplayName("판단 카드: 빈 방이면 제어는 건너뛰고 이유를 남긴다")
    void decisionCardEmptyRoom() {
        store.setOccupancy(1, false, clock.instant());
        feed(5, 24, 0, 680, 0);
        store.setOccupancy(1, false, clock.instant());
        feed(15, 24, 0, 730, 10);
        agent.tick();

        Decision d = co2Card();
        assertThat(d.step(Decision.Step.CONTROL).status()).isEqualTo(Decision.Status.SKIPPED);
        assertThat(d.step(Decision.Step.CONTROL).text()).contains("빈 방");
        assertThat(d.step(Decision.Step.NOTIFY).text()).contains("관리자 화면");
    }

    @Test
    @DisplayName("판단 카드: 센서가 끊기면 센서 카드를 열고 기기는 건드리지 않는다")
    void decisionCardSensor() {
        store.setOccupancy(1, true, clock.instant());
        feed(20, 24, 0, 680, 0);
        clock.advance(Duration.ofMinutes(11));
        agent.tick();

        Decision d = decisions.recent(50).stream().filter(x -> "SENSOR".equals(x.group())).findFirst().orElseThrow();
        assertThat(d.step(Decision.Step.PREDICT).status()).isEqualTo(Decision.Status.SKIPPED);
        assertThat(d.step(Decision.Step.CONTROL).status()).isEqualTo(Decision.Status.SKIPPED);
        assertThat(d.step(Decision.Step.VERIFY).status()).isEqualTo(Decision.Status.RUNNING);
    }
}
