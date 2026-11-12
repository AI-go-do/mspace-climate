package kr.ac.chosun.mspace.climate;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import kr.ac.chosun.mspace.climate.adapter.in.mqtt.MqttSensorClient;
import kr.ac.chosun.mspace.climate.adapter.in.scheduler.ReplayFeeder;
import kr.ac.chosun.mspace.climate.adapter.in.scheduler.SimFeeder;
import kr.ac.chosun.mspace.climate.adapter.in.web.LiveFeed;
import kr.ac.chosun.mspace.climate.adapter.in.web.LiveReading;
import kr.ac.chosun.mspace.climate.adapter.out.CoreRoomStatus;
import kr.ac.chosun.mspace.climate.adapter.out.LogNotifier;
import kr.ac.chosun.mspace.climate.adapter.out.SnapshotFile;
import kr.ac.chosun.mspace.climate.adapter.out.gemini.GeminiJudge;
import kr.ac.chosun.mspace.climate.adapter.out.gemini.JudgeProperties;
import kr.ac.chosun.mspace.climate.appliance.Appliance;
import kr.ac.chosun.mspace.climate.appliance.ApplianceKind;
import kr.ac.chosun.mspace.climate.appliance.ClimateFacade;
import kr.ac.chosun.mspace.climate.appliance.IrAircon;
import kr.ac.chosun.mspace.climate.appliance.TasmotaPlug;
import kr.ac.chosun.mspace.climate.appliance.VirtualAppliance;
import kr.ac.chosun.mspace.climate.application.AlertLog;
import kr.ac.chosun.mspace.climate.application.ClimateAgent;
import kr.ac.chosun.mspace.climate.application.ClimateProperties;
import kr.ac.chosun.mspace.climate.application.ClimateStore;
import kr.ac.chosun.mspace.climate.application.ClimateTools;
import kr.ac.chosun.mspace.climate.application.DecisionBook;
import kr.ac.chosun.mspace.climate.application.Notifier;
import kr.ac.chosun.mspace.climate.application.BoardModel;
import kr.ac.chosun.mspace.climate.application.LabReplay;
import kr.ac.chosun.mspace.climate.application.MqttProperties;
import kr.ac.chosun.mspace.climate.application.ReadingIntake;
import kr.ac.chosun.mspace.climate.application.RoomAnalyzer;
import kr.ac.chosun.mspace.climate.application.RoomSimulator;
import kr.ac.chosun.mspace.climate.application.SensorRegistry;
import kr.ac.chosun.mspace.climate.domain.AnomalyDetector;
import kr.ac.chosun.mspace.climate.domain.BaselineCalculator;
import kr.ac.chosun.mspace.climate.domain.Forecaster;
import kr.ac.chosun.mspace.climate.domain.TrendForecaster;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** 부품 조립. 기기 목록은 설정(climate.appliances)에서 어댑터를 골라 만든다 */
@Configuration
class ClimateConfig {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.system(SEOUL);
    }

    @Bean
    HttpClient applianceHttp() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Bean
    ClimateStore climateStore(ClimateProperties p, Clock clock) {
        return new ClimateStore(p, clock);
    }

    @Bean
    Forecaster forecaster(ClimateProperties p) {
        return new TrendForecaster(p.trendWindow(), p.minTrendPoints());
    }

    @Bean
    RoomAnalyzer roomAnalyzer(ClimateProperties p, Forecaster forecaster, Clock clock) {
        return new RoomAnalyzer(p, new BaselineCalculator(SEOUL, p.k(), p.minSamples()), forecaster,
                new AnomalyDetector(p.lead(), p.stuck()), clock);
    }

    @Bean
    ClimateFacade climateFacade(ClimateProperties p, HttpClient http, Clock clock) {
        return new ClimateFacade(appliances(p, http), p.holdManual(), 500, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    Notifier notifier() {
        return new LogNotifier();
    }

    @Bean
    AlertLog alertLog(ClimateProperties p, Notifier notifier, Clock clock) {
        return new AlertLog(p.alertEvery(), 500, notifier, clock);
    }

    @Bean
    DecisionBook decisionBook() {
        return new DecisionBook(200);
    }

    @Bean
    ClimateAgent climateAgent(ClimateStore store, RoomAnalyzer analyzer, ClimateFacade facade, AlertLog alerts,
                              ClimateProperties p, Clock clock, DecisionBook decisions, Notifier notifier) {
        return new ClimateAgent(store, analyzer, facade, alerts, p, clock, decisions, notifier.reachesStudents());
    }

    @Bean
    CoreRoomStatus coreRoomStatus(@org.springframework.beans.factory.annotation.Value("${climate.core-uri:http://core:8080}") String core,
                                  Clock clock) {
        return new CoreRoomStatus(core, clock);
    }

    @Bean
    ReadingIntake readingIntake(ClimateStore store, ClimateProperties p, ClimateAgent agent, LiveFeed live, Clock clock) {
        return new ReadingIntake(store, p.derive(), agent, (r, source) -> {
            if ("CO2".equals(r.kind())) {
                live.publish("reading", LiveReading.of(r, source));
            }
        }, clock);
    }

    /** 실습실 센서가 멈췄을 때 실측 녹화 재생. climate.replay.enabled=true일 때만 (화면에는 REPLAY로 밝힘) */
    @Bean
    @ConditionalOnProperty(name = "climate.replay.enabled", havingValue = "true")
    ReplayFeeder replayFeeder(ReadingIntake intake, MqttProperties m, Clock clock,
                              @org.springframework.beans.factory.annotation.Value("${climate.replay.resource:replay/lab-1min.csv.gz}") String resource,
                              @org.springframework.beans.factory.annotation.Value("${climate.replay.backfill-minutes:120}") int backfill)
            throws java.io.IOException {
        try (var in = new org.springframework.core.io.ClassPathResource(resource).getInputStream()) {
            return new ReplayFeeder(LabReplay.load(in), intake, m.spots(), backfill, clock);
        }
    }

    /** 방 공기 시뮬레이션 (FR-DEV-107). climate.sim.enabled=true일 때만 (화면에는 SIM으로 밝힘) */
    @Bean
    @ConditionalOnProperty(name = "climate.sim.enabled", havingValue = "true")
    SimFeeder simFeeder(ReadingIntake intake, ClimateFacade facade, ClimateProperties p, Clock clock,
                        @org.springframework.beans.factory.annotation.Value("${climate.sim.backfill-minutes:180}") int backfill,
                        @org.springframework.beans.factory.annotation.Value("${climate.sim.event-every-minutes:25}") double eventEvery,
                        @org.springframework.beans.factory.annotation.Value("${climate.sim.judge-every-seconds:10}") int judgeEvery) {
        java.util.Map<Integer, Integer> cap = new java.util.LinkedHashMap<>();
        p.rooms().keySet().stream().sorted().forEach(id -> cap.put(id, id == 5 ? 8 : 5));
        RoomSimulator sim = new RoomSimulator(cap, eventEvery, SEOUL, System.nanoTime());
        return new SimFeeder(sim, intake, facade, List.copyOf(cap.keySet()), backfill, judgeEvery, clock);
    }

    @Bean
    SensorRegistry sensorRegistry(MqttProperties m) {
        return new SensorRegistry(m.autoRooms() == null ? List.of() : m.autoRooms(), m.spots());
    }

    @Bean
    BoardModel boardModel() {
        return new BoardModel();
    }

    /** 교육장 브로커 직접 구독. climate.mqtt.enabled=true일 때만 (테스트·로컬은 끔) */
    @Bean
    @ConditionalOnProperty(name = "climate.mqtt.enabled", havingValue = "true")
    MqttSensorClient mqttSensorClient(MqttProperties m, ReadingIntake intake, SensorRegistry sensors, BoardModel model,
                                      Clock clock) {
        return new MqttSensorClient(m, intake, sensors, model, clock);
    }

    /** 오디 공기 도구 (MCP로 열림: room_status·set_aircon·set_ventilator·set_purifier). 안전 규칙은 도구 안에 */
    @Bean
    ClimateTools climateTools(ClimateStore store, RoomAnalyzer analyzer, ClimateFacade facade, ClimateProperties p,
                              ReadingIntake intake) {
        return new ClimateTools(store, analyzer, facade, p, intake::people);
    }

    /**
     * 제미나이 판단 에이전트. 키가 있으면 켤 기기를 제미나이가 MCP 도구로 고르고, 없거나 실패하면 규칙이 한다.
     * 끄기(빈 방·평소로 돌아옴)는 언제나 규칙이 한다
     */
    @Bean(destroyMethod = "close")
    GeminiJudge geminiJudge(JudgeProperties jp, ClimateAgent agent, Clock clock) {
        GeminiJudge judge = new GeminiJudge(jp, JsonMapper.builder().build(), clock);
        agent.useJudge(judge);
        return judge;
    }

    @Bean
    SnapshotFile snapshotFile(ClimateProperties p) {
        return new SnapshotFile(p.dataDir());
    }

    /** 설정이 비면 방마다 가상 에어컨·공기청정기·환풍기 (FR-DEV-106 시연) */
    static List<Appliance> appliances(ClimateProperties p, HttpClient http) {
        List<Appliance> list = new ArrayList<>();
        if (p.appliances() == null || p.appliances().isEmpty()) {
            for (int room : p.rooms().keySet()) {
                list.add(new VirtualAppliance("ac-" + room, room, ApplianceKind.AIRCON));
                list.add(new VirtualAppliance("purifier-" + room, room, ApplianceKind.PURIFIER));
                list.add(new VirtualAppliance("fan-" + room, room, ApplianceKind.VENTILATOR));
            }
            return list;
        }
        for (ClimateProperties.ApplianceConfig c : p.appliances()) {
            if (!p.rooms().containsKey(c.room())) {
                throw new IllegalStateException("climate.appliances의 방 번호가 climate.rooms에 없어요: " + c.id());
            }
            String adapter = c.adapter() == null ? "virtual" : c.adapter().toLowerCase();
            list.add(switch (adapter) {
                case "virtual" -> new VirtualAppliance(c.id(), c.room(), c.kind());
                case "ir" -> new IrAircon(c.id(), c.room(), c.brand() == null ? "SAMSUNG" : c.brand(), c.url(), http);
                case "tasmota" -> new TasmotaPlug(c.id(), c.room(), c.kind(), c.brand() == null ? "플러그" : c.brand(),
                        c.url(), http);
                default -> throw new IllegalStateException("모르는 기기 어댑터: " + c.adapter() + " (" + c.id() + ")");
            });
        }
        return list;
    }
}
