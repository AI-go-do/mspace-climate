package kr.ac.chosun.mspace.climate.adapter.out;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kr.ac.chosun.mspace.climate.appliance.ApplianceState;
import kr.ac.chosun.mspace.climate.appliance.ClimateFacade;
import kr.ac.chosun.mspace.climate.application.Alert;
import kr.ac.chosun.mspace.climate.application.AlertLog;
import kr.ac.chosun.mspace.climate.application.ClimateStore;
import kr.ac.chosun.mspace.climate.application.Decision;
import kr.ac.chosun.mspace.climate.application.DecisionBook;
import kr.ac.chosun.mspace.climate.domain.Metric;
import kr.ac.chosun.mspace.climate.domain.Sample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * 재시작해도 기준선(7일 기록)·기기 상태·조치 기록이 이어지게 파일 하나(climate-state.json)에 남긴다.
 * DB를 따로 두지 않는 이유: 방 5개 × 7일 1분 값은 몇 MB라 파일로 충분하고, core DB(지영 담당)를 건드리지 않는다.
 * 임시 파일에 쓰고 바꿔 끼워서(atomic move) 쓰다 꺼져도 앞 파일이 남는다.
 */
public class SnapshotFile {

    private static final Logger log = LoggerFactory.getLogger(SnapshotFile.class);
    static final String NAME = "climate-state.json";

    private final Path file;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public SnapshotFile(String dataDir) {
        this.file = dataDir == null || dataDir.isBlank() ? null : Path.of(dataDir, NAME);
    }

    public void save(ClimateStore store, ClimateFacade facade, AlertLog alerts, DecisionBook decisions) {
        if (file == null) {
            return;
        }
        List<RoomSnap> rooms = new ArrayList<>();
        for (int id : store.roomIds()) {
            var v = store.view(id);
            for (var e : v.history().entrySet()) {
                long[] t = e.getValue().stream().mapToLong(s -> s.at().getEpochSecond()).toArray();
                double[] val = e.getValue().stream().mapToDouble(Sample::value).toArray();
                rooms.add(new RoomSnap(id, e.getKey(), t, val, v.auto()));
            }
        }
        List<ApplianceState> states = new ArrayList<>();
        store.roomIds().forEach(id -> states.addAll(facade.states(id)));
        Snapshot snap = new Snapshot(Instant.now(), rooms, states, facade.recentActions(500), alerts.recent(500),
                decisions.recent(200));
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(NAME + ".tmp");
            json.writeValue(tmp.toFile(), snap);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            log.warn("스냅숏 저장 실패: {}", e.getClass().getSimpleName());
        }
    }

    public void load(ClimateStore store, ClimateFacade facade, AlertLog alerts, DecisionBook decisions) {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            Snapshot snap = json.readValue(file.toFile(), Snapshot.class);
            for (RoomSnap r : snap.rooms()) {
                List<Sample> s = new ArrayList<>(r.t().length);
                for (int i = 0; i < r.t().length; i++) {
                    s.add(new Sample(Instant.ofEpochSecond(r.t()[i]), r.v()[i]));
                }
                store.restore(r.roomId(), r.metric(), s, r.auto());
            }
            snap.appliances().forEach(facade::restore);
            facade.restoreActions(snap.actions());
            alerts.restore(snap.alerts());
            decisions.restore(snap.decisions());
            log.info("스냅숏 불러옴: 방 기록 {}줄", snap.rooms().size());
        } catch (RuntimeException e) {
            log.warn("스냅숏을 못 읽어 빈 기록으로 시작해요: {}", e.getClass().getSimpleName());
        }
    }

    record Snapshot(Instant savedAt, List<RoomSnap> rooms, List<ApplianceState> appliances,
                    List<ClimateFacade.Action> actions, List<Alert> alerts, List<Decision> decisions) {
    }

    record RoomSnap(int roomId, Metric metric, long[] t, double[] v, boolean auto) {
    }

}
