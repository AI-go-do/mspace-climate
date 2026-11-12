package kr.ac.chosun.mspace.climate.application;

import java.time.Clock;
import java.util.List;
import kr.ac.chosun.mspace.climate.appliance.ApplianceCommand;
import kr.ac.chosun.mspace.climate.appliance.ApplianceKind;
import kr.ac.chosun.mspace.climate.appliance.ApplianceState;
import kr.ac.chosun.mspace.climate.appliance.ClimateFacade;
import kr.ac.chosun.mspace.climate.domain.Baseline;
import kr.ac.chosun.mspace.climate.domain.Finding;
import kr.ac.chosun.mspace.climate.domain.Finding.Kind;
import kr.ac.chosun.mspace.climate.domain.Metric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 오디 공기 관리 (FR-DEV-106 "에이전트가 기준값으로 알아서 켜고 끈다", 재웅 11/12 "제어는 모두 AI가").
 * 1분마다 방마다: 분석(기준선·예측·이상) → 알림 → 기기 조작. 사람(관리자)은 아무것도 안 한다.
 *
 * <p>판단 순서 (안전장치가 먼저)
 * <ol>
 *   <li>자동 끔(auto=false)인 방은 알림만, 기기는 안 건드림</li>
 *   <li>빈 방이 emptyOff(10분) 넘게 이어지면 다 끔</li>
 *   <li>사람이 있을 때만 켠다. 재실을 모르면(UNKNOWN) 켜지 않는다</li>
 *   <li>온도: 곧 넘음·넘음 → 냉방, 곧 밑돎·밑돎 → 난방. 목표 온도는 이 방 평소 평균(설정 범위 안)</li>
 *   <li>습도: 온도 조작이 없을 때만 제습</li>
 *   <li>CO2: 공기청정기는 CO2를 못 낮춘다 → 방 사용자에게 환기 알림 + 환풍기(있으면). 창문을 열면 들어오는 먼지용으로 공기청정기</li>
 *   <li>평소(평균)로 돌아오면 끔 (켰다 껐다 반복을 막는 히스테리시스)</li>
 * </ol>
 * 사람이 손으로 바꾼 기기는 파사드가 holdManual(30분) 동안 막는다.
 */
public class ClimateAgent {

    private static final Logger log = LoggerFactory.getLogger(ClimateAgent.class);

    private final ClimateStore store;
    private final RoomAnalyzer analyzer;
    private final ClimateFacade facade;
    private final AlertLog alerts;
    private final ClimateProperties props;
    private final Clock clock;

    public ClimateAgent(ClimateStore store, RoomAnalyzer analyzer, ClimateFacade facade, AlertLog alerts,
                        ClimateProperties props, Clock clock) {
        this.store = store;
        this.analyzer = analyzer;
        this.facade = facade;
        this.alerts = alerts;
        this.props = props;
        this.clock = clock;
    }

    /** 모든 방 한 바퀴. 한 방에서 오류가 나도 다른 방은 계속 본다 */
    public void tick() {
        for (int roomId : store.roomIds()) {
            try {
                act(analyzer.analyze(store.view(roomId)));
            } catch (RuntimeException e) {
                log.warn("방 {} 판단 실패: {}", roomId, e.getClass().getSimpleName());
            }
        }
    }

    void act(RoomAnalysis a) {
        raiseAlerts(a);
        if (!a.auto()) {
            return;
        }
        int room = a.roomId();
        if (Boolean.FALSE.equals(a.occupied()) && a.occupiedSince() != null
                && a.occupiedSince().plus(props.emptyOff()).isBefore(clock.instant())) {
            facade.allOff(room, "빈 방 %d분째라 껐어요".formatted(props.emptyOff().toMinutes()));
            return;
        }
        if (!Boolean.TRUE.equals(a.occupied()) || a.offline()) {
            return;
        }
        boolean airconBusy = temperature(a);
        if (!airconBusy) {
            humidity(a);
        }
        co2(a);
    }

    /** 에어컨을 냉·난방으로 쓰는 중이면 true */
    private boolean temperature(RoomAnalysis a) {
        var t = a.metrics().get(Metric.TEMPERATURE);
        if (t.latest() == null) {
            return false;
        }
        Finding hot = t.first(Kind.OVER, Kind.RISING_SOON);
        Finding cold = t.first(Kind.UNDER, Kind.FALLING_SOON);
        int room = a.roomId();
        if (hot != null) {
            int target = target(t.baseline(), props.comfort().coolMin(), props.comfort().coolMax());
            facade.cool(room, target, hot.message() + " → 냉방 %d도".formatted(target));
            return true;
        }
        if (cold != null) {
            int target = target(t.baseline(), props.comfort().heatMin(), props.comfort().heatMax());
            facade.heat(room, target, cold.message() + " → 난방 %d도".formatted(target));
            return true;
        }
        ApplianceCommand.Mode mode = airconMode(room);
        Double mean = t.baseline().mean();
        double v = t.latest().value();
        if (mode == ApplianceCommand.Mode.COOL && mean != null && v <= mean) {
            facade.airconOff(room, "온도 %s°C, 이 방 평소(%s°C)로 돌아와 껐어요".formatted(fmt(v), fmt(mean)));
        } else if (mode == ApplianceCommand.Mode.HEAT && mean != null && v >= mean) {
            facade.airconOff(room, "온도 %s°C, 이 방 평소(%s°C)로 돌아와 껐어요".formatted(fmt(v), fmt(mean)));
        }
        return mode == ApplianceCommand.Mode.COOL || mode == ApplianceCommand.Mode.HEAT;
    }

    private void humidity(RoomAnalysis a) {
        var h = a.metrics().get(Metric.HUMIDITY);
        if (h.latest() == null) {
            return;
        }
        Finding wet = h.first(Kind.OVER, Kind.RISING_SOON);
        int room = a.roomId();
        if (wet != null) {
            facade.dry(room, wet.message() + " → 제습");
            return;
        }
        Double mean = h.baseline().mean();
        if (airconMode(room) == ApplianceCommand.Mode.DRY && mean != null && h.latest().value() <= mean) {
            facade.airconOff(room, "습도 %s%%, 이 방 평소(%s%%)로 돌아와 껐어요".formatted(fmt(h.latest().value()),
                    fmt(mean)));
        }
    }

    private void co2(RoomAnalysis a) {
        var c = a.metrics().get(Metric.CO2);
        if (c.latest() == null) {
            return;
        }
        Finding high = c.first(Kind.OVER, Kind.RISING_SOON);
        int room = a.roomId();
        if (high != null) {
            facade.set(room, ApplianceKind.VENTILATOR, true, high.message() + " → 환풍기");
            facade.set(room, ApplianceKind.PURIFIER, true, "창문 환기 중 들어오는 먼지 거르기 (공기청정기는 CO2를 못 낮춰요)");
            return;
        }
        Double mean = c.baseline().mean();
        if (mean != null && c.latest().value() <= mean) {
            String why = "CO2 %sppm, 이 방 평소(%sppm)로 돌아와 껐어요".formatted(fmt(c.latest().value()), fmt(mean));
            facade.set(room, ApplianceKind.VENTILATOR, false, why);
            facade.set(room, ApplianceKind.PURIFIER, false, why);
        }
    }

    private void raiseAlerts(RoomAnalysis a) {
        for (Finding f : a.findings()) {
            boolean forRoom = f.actionable() && Boolean.TRUE.equals(a.occupied());
            String msg = f.metric() == Metric.CO2 && forRoom
                    ? f.message() + ". 창문이나 문을 5–10분 열어 환기해 주세요"
                    : f.message();
            alerts.raise(new Alert(clock.instant(), a.roomId(), forRoom ? Alert.Audience.ROOM : Alert.Audience.ADMIN,
                    f.metric(), f.kind(), a.name() + ": " + msg, f.etaMinutes()));
        }
    }

    private ApplianceCommand.Mode airconMode(int roomId) {
        List<ApplianceState> s = facade.states(roomId).stream()
                .filter(x -> x.kind() == ApplianceKind.AIRCON && x.power() == ApplianceCommand.Power.ON).toList();
        return s.isEmpty() ? null : s.get(0).mode();
    }

    /** 목표 온도 = 이 방 평소 평균을 반올림해 [min, max] 안으로. 평균을 아직 모르면 가운데 */
    static int target(Baseline b, int min, int max) {
        if (b.mean() == null) {
            return (min + max) / 2;
        }
        return (int) Math.max(min, Math.min(max, Math.round(b.mean())));
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format("%.1f", v);
    }
}
