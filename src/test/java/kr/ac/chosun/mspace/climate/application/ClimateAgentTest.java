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
                Map.of(Metric.CO2, new MetricRule(null, 1000.0, 150, 400, false, null),
                        Metric.TEMPERATURE, new MetricRule(18.0, 28.0, 1.5, 4, false, null),
                        Metric.HUMIDITY, new MetricRule(30.0, 70.0, 8, 20, false, null)),
                new ClimateProperties.Comfort(24, 26, 20, 22), List.of(), null, null, null);
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

    /** 1분마다 CO2 값 하나(살짝 흔들어 센서 멈춤으로 안 보이게) */
    void co2For(int minutes, int ppm) {
        for (int i = 0; i < minutes; i++) {
            feed(1, 23.0 + (i % 2) * 0.2, 0, ppm + (i % 3) * 6, 0);
            clock.advance(Duration.ofSeconds(30));
        }
    }

    @Test
    @DisplayName("오래 붐벼 평소 CO2가 기준(1000)보다 높은 방도, 기준 바로 아래(950)에서 끄지 않고 기준-여유폭(850) 아래로 내려와야 끈다")
    void co2OffBelowLimitNotBusyMean() {
        // given: 2시간 1200ppm 언저리(평소가 1000보다 높음) → 환기 켜짐
        store.setOccupancy(1, true, clock.instant());
        co2For(120, 1200);
        agent.tick();
        assertThat(purifier().power()).isEqualTo(ApplianceCommand.Power.ON);
        // when: 950으로 내려옴(평소 아래지만 기준 바로 밑)
        co2For(10, 950);
        agent.tick();
        // then: 아직 켜 둠
        assertThat(purifier().power()).isEqualTo(ApplianceCommand.Power.ON);
        // when: 820으로 더 내려옴
        co2For(10, 820);
        agent.tick();
        // then: 끔
        assertThat(purifier().power()).isEqualTo(ApplianceCommand.Power.OFF);
        assertThat(facade.recentActions(1).get(0).reason()).contains("기준보다 충분히 낮아져");
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
    @DisplayName("판단 카드: 이상이 잠깐 사라져도 settle(5분) 동안은 닫지 않는다 — 경계 언저리에서 카드가 1~2분마다 새로 열리지 않게")
    void decisionCardSettles() {
        // given: settle 5분인 오디
        ClimateProperties p = new ClimateProperties(props.rooms(), props.keep(), props.k(), props.minSamples(),
                props.trendWindow(), props.minTrendPoints(), props.lead(), props.stuck(), props.offline(), props.emptyOff(),
                props.holdManual(), props.alertEvery(), props.rules(), props.comfort(), props.appliances(), null, null,
                Duration.ofMinutes(5));
        RoomAnalyzer analyzer = new RoomAnalyzer(p, new BaselineCalculator(ZoneId.of("Asia/Seoul"), 2.5, 30),
                new TrendForecaster(Duration.ofMinutes(30), 5),
                new AnomalyDetector(Duration.ofMinutes(15), Duration.ofMinutes(20)), clock);
        agent = new ClimateAgent(store, analyzer, facade, alerts, p, clock, decisions, false);
        store.setOccupancy(1, true, clock.instant());
        feed(20, 24, 0, 680, 10);
        agent.tick();

        // when: 값이 평소로 돌아온 첫 판단
        feed(25, 24, 0, 600, 0);
        agent.tick();

        // then: 아직 열려 있다
        assertThat(co2Card().open()).isTrue();

        // when: 5분 넘게 계속 평소
        feed(6, 24, 0, 600, 0);
        agent.tick();

        // then: 카드 하나가 "돌아왔어요"로 닫힌다
        assertThat(co2Card().open()).isFalse();
        assertThat(co2Card().step(Decision.Step.VERIFY).text()).contains("평소 범위로 돌아왔어요");
        assertThat(decisions.recent(50).stream().filter(d -> d.metric() == Metric.CO2 && "HIGH".equals(d.group())))
                .hasSize(1);
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

    /** 손으로 답하는 가짜 판단기: 받은 요청과 답 통로만 들고 있는다 */
    static class FakeJudge implements Judge {
        final List<Request> asked = new ArrayList<>();
        Listener listener;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void ask(Request request, Listener l) {
            asked.add(request);
            listener = l;
        }
    }

    @Test
    @DisplayName("판단 에이전트: 켜야 할 일이면 규칙은 켜지 않고 오디 AI에게 넘기며, 제어 단계는 고르는 중으로 남는다")
    void judgeTakesOn() {
        FakeJudge judge = new FakeJudge();
        agent.useJudge(judge);
        store.setOccupancy(1, true, clock.instant());
        feed(20, 24, 0, 680, 10);

        agent.tick();

        assertThat(purifier().power()).isEqualTo(ApplianceCommand.Power.OFF);
        assertThat(judge.asked).hasSize(1);
        assertThat(judge.asked.get(0).roomId()).isEqualTo(1);
        assertThat(judge.asked.get(0).findings()).anyMatch(f -> f.contains("CO2"));
        Decision d = co2Card();
        assertThat(d.step(Decision.Step.CONTROL).status()).isEqualTo(Decision.Status.RUNNING);
        assertThat(judge.asked.get(0).decisionIds()).contains(d.id());
    }

    @Test
    @DisplayName("판단 에이전트: 오디 AI가 기기를 바꾸면 제어 단계에 한 일과 이유가 남는다")
    void judgeDone() {
        FakeJudge judge = new FakeJudge();
        agent.useJudge(judge);
        store.setOccupancy(1, true, clock.instant());
        feed(20, 24, 0, 680, 10);
        agent.tick();

        judge.listener.progress(judge.asked.get(0), "공기청정기 켜는 중");
        assertThat(co2Card().step(Decision.Step.CONTROL).text()).isEqualTo("공기청정기 켜는 중");
        judge.listener.done(judge.asked.get(0), List.of("공기청정기 켬"), "CO2가 오르고 있어 공기청정기를 켰어요");

        Decision d = co2Card();
        assertThat(d.step(Decision.Step.CONTROL).status()).isEqualTo(Decision.Status.DONE);
        assertThat(d.step(Decision.Step.CONTROL).text()).contains("공기청정기 켬").contains("CO2가 오르고 있어");
    }

    @Test
    @DisplayName("판단 에이전트: 오디 AI가 답을 못 하면 규칙이 대신 켜고 '규칙으로 판단'이라고 밝힌다")
    void judgeFallback() {
        FakeJudge judge = new FakeJudge();
        agent.useJudge(judge);
        store.setOccupancy(1, true, clock.instant());
        feed(20, 24, 0, 680, 10);
        agent.tick();

        judge.listener.fallback(judge.asked.get(0), "제미나이 응답 없음");

        assertThat(purifier().power()).isEqualTo(ApplianceCommand.Power.ON);
        Decision d = co2Card();
        assertThat(d.step(Decision.Step.CONTROL).status()).isEqualTo(Decision.Status.DONE);
        assertThat(d.step(Decision.Step.CONTROL).text()).startsWith("규칙으로 판단(제미나이 응답 없음)");
    }

    @Test
    @DisplayName("판단 에이전트: 끄기(빈 방)는 오디 AI에게 묻지 않고 규칙이 바로 한다")
    void judgeNotForOff() {
        FakeJudge judge = new FakeJudge();
        agent.useJudge(judge);
        facade.cool(1, 25, "앞서 켬");
        store.setOccupancy(1, false, clock.instant());
        feed(11, 24, 0, 600, 0);

        agent.tick();

        assertThat(ac().power()).isEqualTo(ApplianceCommand.Power.OFF);
        assertThat(judge.asked).isEmpty();
    }
}
