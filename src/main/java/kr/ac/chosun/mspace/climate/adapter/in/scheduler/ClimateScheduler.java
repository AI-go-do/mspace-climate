package kr.ac.chosun.mspace.climate.adapter.in.scheduler;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kr.ac.chosun.mspace.climate.adapter.in.web.LiveFeed;
import kr.ac.chosun.mspace.climate.adapter.out.CoreRoomStatus;
import kr.ac.chosun.mspace.climate.adapter.out.SnapshotFile;
import kr.ac.chosun.mspace.climate.appliance.ClimateFacade;
import kr.ac.chosun.mspace.climate.application.AlertLog;
import kr.ac.chosun.mspace.climate.application.ClimateAgent;
import kr.ac.chosun.mspace.climate.application.ClimateStore;
import kr.ac.chosun.mspace.climate.application.DecisionBook;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 1분마다 재실 읽기 → 오디 판단, 5분마다 스냅숏. climate.scheduler=false면 끔(테스트) */
@Component
@ConditionalOnProperty(name = "climate.scheduler", havingValue = "true", matchIfMissing = true)
class ClimateScheduler {

    private final ClimateAgent agent;
    private final SnapshotFile snapshot;
    private final ClimateStore store;
    private final ClimateFacade facade;
    private final AlertLog alerts;
    private final CoreRoomStatus roomStatus;
    private final LiveFeed live;
    private final DecisionBook decisions;
    /** 시뮬레이션 중에는 재실도 시뮬레이션이 정한다(core 재실로 덮지 않음) */
    @org.springframework.beans.factory.annotation.Value("${climate.sim.enabled:false}")
    private boolean sim;

    ClimateScheduler(ClimateAgent agent, SnapshotFile snapshot, ClimateStore store, ClimateFacade facade,
                     AlertLog alerts, CoreRoomStatus roomStatus, LiveFeed live, DecisionBook decisions) {
        this.decisions = decisions;
        this.roomStatus = roomStatus;
        this.live = live;
        this.agent = agent;
        this.snapshot = snapshot;
        this.store = store;
        this.facade = facade;
        this.alerts = alerts;
    }

    @PostConstruct
    void load() {
        snapshot.load(store, facade, alerts, decisions);
    }

    @Scheduled(fixedDelayString = "${climate.tick:60s}", initialDelayString = "${climate.tick:60s}")
    void tick() {
        if (!sim) {
            roomStatus.pull(store);
        }
        agent.tick();
        live.publish("tick", java.util.Map.of());
    }

    @Scheduled(fixedDelayString = "5m", initialDelayString = "5m")
    @PreDestroy
    void save() {
        snapshot.save(store, facade, alerts, decisions);
    }
}
