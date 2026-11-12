package kr.ac.chosun.mspace.climate;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import kr.ac.chosun.mspace.climate.adapter.out.CoreRoomStatus;
import kr.ac.chosun.mspace.climate.adapter.out.LogNotifier;
import kr.ac.chosun.mspace.climate.adapter.out.SnapshotFile;
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
import kr.ac.chosun.mspace.climate.application.Notifier;
import kr.ac.chosun.mspace.climate.application.RoomAnalyzer;
import kr.ac.chosun.mspace.climate.domain.AnomalyDetector;
import kr.ac.chosun.mspace.climate.domain.BaselineCalculator;
import kr.ac.chosun.mspace.climate.domain.Forecaster;
import kr.ac.chosun.mspace.climate.domain.TrendForecaster;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    ClimateAgent climateAgent(ClimateStore store, RoomAnalyzer analyzer, ClimateFacade facade, AlertLog alerts,
                              ClimateProperties p, Clock clock) {
        return new ClimateAgent(store, analyzer, facade, alerts, p, clock);
    }

    @Bean
    CoreRoomStatus coreRoomStatus(@org.springframework.beans.factory.annotation.Value("${climate.core-uri:http://core:8080}") String core,
                                  Clock clock) {
        return new CoreRoomStatus(core, clock);
    }

    @Bean
    SnapshotFile snapshotFile(ClimateProperties p) {
        return new SnapshotFile(p.dataDir());
    }

    /** 설정이 비면 방마다 가상 에어컨·공기청정기 (FR-DEV-106 시연) */
    static List<Appliance> appliances(ClimateProperties p, HttpClient http) {
        List<Appliance> list = new ArrayList<>();
        if (p.appliances() == null || p.appliances().isEmpty()) {
            for (int room : p.rooms().keySet()) {
                list.add(new VirtualAppliance("ac-" + room, room, ApplianceKind.AIRCON));
                list.add(new VirtualAppliance("purifier-" + room, room, ApplianceKind.PURIFIER));
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
