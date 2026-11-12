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
import kr.ac.chosun.mspace.climate.adapter.out.CoreAirAlertNotifier;
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

    /**
     * 방 사용자 알림. CLIMATE_SERVICE_TOKEN이 있으면 core에 부탁하고(core가 그 방 사람에게 보냄),
     * 비면 지금처럼 관리자 화면에만 보인다
     */
    @Bean
    @ConditionalOnMissingBean
    Notifier notifier(HttpClient http,
                      @org.springframework.beans.factory.annotation.Value("${climate.notify.core-url:http://core:8080}") String coreUrl,
                      @org.springframework.beans.factory.annotation.Value("${climate.notify.token:}") String token) {
        return token == null || token.isBlank() ? new LogNotifier() : new CoreAirAlertNotifier(coreUrl, token, http);
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
                                  Clock clock, ClimateProperties p,
                                  @org.springframework.beans.factory.annotation.Value("${climate.sim.enabled:false}") boolean sim,
                                  @org.springframework.beans.factory.annotation.Value("${climate.sim.real-rooms:}") List<Integer> real) {
        // 시뮬레이션 중이면 실제 방(real-rooms) 말고는 core 재실을 쓰지 않는다(가상 방 손님은 시뮬레이션이 만듦)
        java.util.Set<Integer> skip = sim ? virtualRooms(p, real) : java.util.Set.of();
        return new CoreRoomStatus(core, clock, skip);
    }

    /** 시뮬레이션이 손님을 만드는 방 = 설정한 방 중 real-rooms에 없는 방 */
    static java.util.Set<Integer> virtualRooms(ClimateProperties p, List<Integer> real) {
        java.util.Set<Integer> out = new java.util.TreeSet<>(p.rooms().keySet());
        if (real != null) {
            out.removeAll(real);
        }
        return out;
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
    SimFeeder simFeeder(ReadingIntake intake, ClimateStore store, ClimateFacade facade, ClimateProperties p, Clock clock,
                        @org.springframework.beans.factory.annotation.Value("${climate.sim.backfill-minutes:180}") int backfill,
                        @org.springframework.beans.factory.annotation.Value("${climate.sim.event-every-minutes:25}") double eventEvery,
                        @org.springframework.beans.factory.annotation.Value("${climate.sim.judge-every-seconds:10}") int judgeEvery,
                        @org.springframework.beans.factory.annotation.Value("${climate.sim.real-rooms:}") List<Integer> real,
                        @org.springframework.beans.factory.annotation.Value("${climate.sim.big-rooms:5}") List<Integer> big,
                        @org.springframework.beans.factory.annotation.Value("${climate.sim.people-small:3,5}") List<Integer> small,
                        @org.springframework.beans.factory.annotation.Value("${climate.sim.people-big:5,8}") List<Integer> bigPeople,
                        @org.springframework.beans.factory.annotation.Value("${climate.sim.gap-minutes:10,40}") List<Integer> gap,
                        @org.springframework.beans.factory.annotation.Value("${climate.sim.stay-minutes:50,120}") List<Integer> stay) {
        // 실제 방에 사람이 있을 때 가정하는 인원(core가 인원을 안 알려 줌): 작은 방은 최소~최대 가운데, 큰 방도 같게
        java.util.Map<Integer, Integer> people = new java.util.LinkedHashMap<>();
        java.util.Map<Integer, int[]> range = new java.util.LinkedHashMap<>();
        java.util.Set<Integer> virtual = virtualRooms(p, real);
        p.rooms().keySet().stream().sorted().forEach(id -> {
            List<Integer> r = big.contains(id) ? bigPeople : small;
            people.put(id, (r.get(0) + r.get(1) + 1) / 2);
            if (virtual.contains(id)) {
                range.put(id, new int[] {r.get(0), r.get(1)});
            }
        });
        RoomSimulator sim = new RoomSimulator(people, eventEvery, SEOUL, System.nanoTime(),
                new RoomSimulator.Visitors(range, gap.get(0), gap.get(1), stay.get(0), stay.get(1)));
        return new SimFeeder(sim, intake, store, facade, List.copyOf(people.keySet()), backfill, judgeEvery, clock);
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
    ClimateTools climateTools(ClimateStore store, RoomAnalyzer analyzer, ClimateFacade facade, ClimateProperties p) {
        return new ClimateTools(store, analyzer, facade, p);
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
