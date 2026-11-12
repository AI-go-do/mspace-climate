package kr.ac.chosun.mspace.climate.adapter.in.scheduler;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kr.ac.chosun.mspace.climate.adapter.out.CoreRoomStatus;
import kr.ac.chosun.mspace.climate.adapter.out.SnapshotFile;
import kr.ac.chosun.mspace.climate.appliance.ClimateFacade;
import kr.ac.chosun.mspace.climate.application.AlertLog;
import kr.ac.chosun.mspace.climate.application.ClimateAgent;
import kr.ac.chosun.mspace.climate.application.ClimateStore;
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

    ClimateScheduler(ClimateAgent agent, SnapshotFile snapshot, ClimateStore store, ClimateFacade facade,
                     AlertLog alerts, CoreRoomStatus roomStatus) {
        this.roomStatus = roomStatus;
        this.agent = agent;
        this.snapshot = snapshot;
        this.store = store;
        this.facade = facade;
        this.alerts = alerts;
    }

    @PostConstruct
    void load() {
        snapshot.load(store, facade, alerts);
    }

    @Scheduled(fixedDelayString = "${climate.tick:60s}", initialDelayString = "${climate.tick:60s}")
    void tick() {
        roomStatus.pull(store);
        agent.tick();
    }

    @Scheduled(fixedDelayString = "5m", initialDelayString = "5m")
    @PreDestroy
    void save() {
        snapshot.save(store, facade, alerts);
    }
}
