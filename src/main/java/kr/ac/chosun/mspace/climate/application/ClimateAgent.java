package kr.ac.chosun.mspace.climate.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.chosun.mspace.climate.appliance.ApplianceCommand;
import kr.ac.chosun.mspace.climate.appliance.ApplianceKind;
import kr.ac.chosun.mspace.climate.appliance.ApplianceState;
import kr.ac.chosun.mspace.climate.appliance.ClimateFacade;
import kr.ac.chosun.mspace.climate.appliance.ClimateFacade.Action;
import kr.ac.chosun.mspace.climate.application.Decision.Status;
import kr.ac.chosun.mspace.climate.application.Decision.Step;
import kr.ac.chosun.mspace.climate.domain.Baseline;
import kr.ac.chosun.mspace.climate.domain.Finding;
import kr.ac.chosun.mspace.climate.domain.Finding.Kind;
import kr.ac.chosun.mspace.climate.domain.Metric;
import kr.ac.chosun.mspace.climate.domain.Sample;
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
 *
 * <p>판단마다 판단 카드(Decision)를 남긴다: 탐지 → 예측 → 알림 → 제어 → 다시 확인. 값이 평소로 돌아오면 다시 확인이 끝나고,
 * alertEvery(30분) 안에 안 돌아오면 "그대로"로 닫고 다음 판단에서 새 카드를 연다.
 * 이상이 settle(5분) 동안 계속 없어야 "돌아왔어요"로 닫는다. 경계 언저리에서 카드가 1~2분마다 열렸다 닫히지 않게.
 * 센서 값이 들어올 때마다(tickRoom) 그 방을 바로 본다. 1분 tick은 재실 변화·끊김을 잡는 안전망.
 */
public class ClimateAgent {

    private static final Logger log = LoggerFactory.getLogger(ClimateAgent.class);

    private final ClimateStore store;
    private final RoomAnalyzer analyzer;
    private final ClimateFacade facade;
    private final AlertLog alerts;
    private final ClimateProperties props;
    private final Clock clock;
    private final DecisionBook decisions;
    /** 열린 카드의 이상이 처음 사라진 시각. settle 동안 계속 없어야 닫는다 */
    private final Map<String, Instant> quietSince = new HashMap<>();
    private final boolean reachesStudents;
    /** 판단 에이전트(제미나이). 없거나 키가 없으면 규칙만으로 켠다 */
    private volatile Judge judge;

    public ClimateAgent(ClimateStore store, RoomAnalyzer analyzer, ClimateFacade facade, AlertLog alerts,
                        ClimateProperties props, Clock clock, DecisionBook decisions, boolean reachesStudents) {
        this.decisions = decisions;
        this.reachesStudents = reachesStudents;
        this.store = store;
        this.analyzer = analyzer;
        this.facade = facade;
        this.alerts = alerts;
        this.props = props;
        this.clock = clock;
    }

    /**
     * 판단 에이전트를 붙인다(재웅 11/12 "실제로 판단"). 붙어 있으면 새 이상이 생긴 방은 규칙이 켜지 않고 에이전트에게 맡긴다.
     * 끄기(평소로 돌아옴·빈 방)는 그대로 규칙이 한다(안전한 쪽)
     */
    public void useJudge(Judge judge) {
        this.judge = judge;
    }

    private boolean judging() {
        Judge j = judge;
        return j != null && j.enabled();
    }

    /** 모든 방 한 바퀴. 한 방에서 오류가 나도 다른 방은 계속 본다 */
    public synchronized void tick() {
        for (int roomId : store.roomIds()) {
            tickRoom(roomId);
        }
    }

    /** 방 하나만 지금 본다 (센서 값이 들어온 순간) */
    public synchronized void tickRoom(int roomId) {
        try {
            act(analyzer.analyze(store.view(roomId)));
        } catch (RuntimeException e) {
            log.warn("방 {} 판단 실패: {}", roomId, e.getClass().getSimpleName());
        }
    }

    void act(RoomAnalysis a) {
        Map<String, Alert> sent = raiseAlerts(a);
        Map<Metric, List<Action>> acted = new EnumMap<>(Metric.class);
        Map<Metric, String> skip = new EnumMap<>(Metric.class);
        boolean judged = judging();
        String why = control(a, acted, skip, !judged);
        record(a, sent, acted, skip, why, judged);
    }

    /**
     * 기기 조작. 안 움직였으면 왜 안 움직였는지 돌려준다(판단 카드 제어 단계 문구).
     * allowOn=false면 켜기는 하지 않는다(판단 에이전트가 고름). 끄기는 그대로
     */
    private String control(RoomAnalysis a, Map<Metric, List<Action>> acted, Map<Metric, String> skip, boolean allowOn) {
        if (!a.auto()) {
            return "자동 제어를 꺼 둔 방이라 기기는 그대로예요";
        }
        int room = a.roomId();
        if (Boolean.FALSE.equals(a.occupied()) && a.occupiedSince() != null
                && a.occupiedSince().plus(props.emptyOff()).isBefore(clock.instant())) {
            facade.allOff(room, "빈 방 %d분째라 껐어요".formatted(props.emptyOff().toMinutes()));
            return "빈 방이라 켜지 않았어요";
        }
        if (a.offline()) {
            return "센서가 끊겨 기기를 안 건드려요";
        }
        if (a.occupied() == null) {
            return "사람이 있는지 몰라 켜지 않았어요";
        }
        if (!a.occupied()) {
            return "빈 방이라 켜지 않았어요";
        }
        Action head = head();
        boolean airconBusy = temperature(a, allowOn);
        acted.put(Metric.TEMPERATURE, since(head));
        head = head();
        if (airconBusy) {
            skip.put(Metric.HUMIDITY, "에어컨이 냉난방 중이라 제습은 미뤘어요");
        } else {
            humidity(a, allowOn);
            acted.put(Metric.HUMIDITY, since(head));
        }
        head = head();
        co2(a, allowOn);
        acted.put(Metric.CO2, since(head));
        return null;
    }

    private Action head() {
        List<Action> l = facade.recentActions(1);
        return l.isEmpty() ? null : l.get(0);
    }

    private List<Action> since(Action head) {
        List<Action> out = new ArrayList<>();
        for (Action x : facade.recentActions(50)) {
            if (x == head) {
                break;
            }
            out.add(0, x);
        }
        return out;
    }

    /** 에어컨을 냉·난방으로 쓰는 중이면 true */
    private boolean temperature(RoomAnalysis a, boolean allowOn) {
        var t = a.metrics().get(Metric.TEMPERATURE);
        if (t.latest() == null) {
            return false;
        }
        Finding hot = t.first(Kind.OVER, Kind.RISING_SOON);
        Finding cold = t.first(Kind.UNDER, Kind.FALLING_SOON);
        int room = a.roomId();
        if ((hot != null || cold != null) && !allowOn) {
            return airconMode(room) == ApplianceCommand.Mode.COOL || airconMode(room) == ApplianceCommand.Mode.HEAT;
        }
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
            facade.airconOff(room, "온도 %s°C, 이 방 평소(%s°C)로 돌아와 껐어요".formatted(Metric.TEMPERATURE.format(v), Metric.TEMPERATURE.format(mean)));
        } else if (mode == ApplianceCommand.Mode.HEAT && mean != null && v >= mean) {
            facade.airconOff(room, "온도 %s°C, 이 방 평소(%s°C)로 돌아와 껐어요".formatted(Metric.TEMPERATURE.format(v), Metric.TEMPERATURE.format(mean)));
        }
        return mode == ApplianceCommand.Mode.COOL || mode == ApplianceCommand.Mode.HEAT;
    }

    private void humidity(RoomAnalysis a, boolean allowOn) {
        var h = a.metrics().get(Metric.HUMIDITY);
        if (h.latest() == null) {
            return;
        }
        Finding wet = h.first(Kind.OVER, Kind.RISING_SOON);
        int room = a.roomId();
        if (wet != null && !allowOn) {
            return;
        }
        if (wet != null) {
            facade.dry(room, wet.message() + " → 제습");
            return;
        }
        Double mean = h.baseline().mean();
        if (airconMode(room) == ApplianceCommand.Mode.DRY && mean != null && h.latest().value() <= mean) {
            facade.airconOff(room, "습도 %s%%, 이 방 평소(%s%%)로 돌아와 껐어요".formatted(Metric.HUMIDITY.format(h.latest().value()),
                    Metric.HUMIDITY.format(mean)));
        }
    }

    private void co2(RoomAnalysis a, boolean allowOn) {
        var c = a.metrics().get(Metric.CO2);
        if (c.latest() == null) {
            return;
        }
        Finding high = c.first(Kind.OVER, Kind.RISING_SOON);
        int room = a.roomId();
        if (high != null && !allowOn) {
            return;
        }
        if (high != null) {
            facade.set(room, ApplianceKind.VENTILATOR, true, high.message() + " → 환풍기");
            facade.set(room, ApplianceKind.PURIFIER, true, "창문 환기 중 들어오는 먼지 거르기 (공기청정기는 CO2를 못 낮춰요)");
            return;
        }
        Double mean = c.baseline().mean();
        if (mean != null && c.latest().value() <= mean) {
            String why = "CO2 %sppm, 이 방 평소(%sppm)로 돌아와 껐어요".formatted(Metric.CO2.format(c.latest().value()), Metric.CO2.format(mean));
            facade.set(room, ApplianceKind.VENTILATOR, false, why);
            facade.set(room, ApplianceKind.PURIFIER, false, why);
        }
    }

    /** 보낸 알림을 판단 묶음(방·지표·방향)별로 돌려준다 */
    private Map<String, Alert> raiseAlerts(RoomAnalysis a) {
        Map<String, Alert> sent = new HashMap<>();
        for (Finding f : a.findings()) {
            boolean forRoom = f.actionable() && Boolean.TRUE.equals(a.occupied());
            String msg = f.metric() == Metric.CO2 && forRoom
                    ? f.message() + ". 창문이나 문을 5–10분 열어 환기해 주세요"
                    : f.message();
            Alert alert = new Alert(clock.instant(), a.roomId(), forRoom ? Alert.Audience.ROOM : Alert.Audience.ADMIN,
                    f.metric(), f.kind(), a.name() + ": " + msg, f.etaMinutes());
            if (alerts.raise(alert)) {
                sent.put(Decision.key(a.roomId(), f.metric(), group(f.kind())), alert);
            }
        }
        return sent;
    }

    // ---------- 판단 카드 ----------

    private void record(RoomAnalysis a, Map<String, Alert> sent, Map<Metric, List<Action>> acted,
                        Map<Metric, String> skip, String why, boolean judged) {
        Instant now = clock.instant();
        List<String> handIds = new ArrayList<>();
        List<String> handFindings = new ArrayList<>();
        Map<String, Finding> seen = new HashMap<>();
        for (Finding f : a.findings()) {
            seen.putIfAbsent(Decision.key(a.roomId(), f.metric(), group(f.kind())), f);
        }
        for (var e : seen.entrySet()) {
            quietSince.remove(e.getKey());
            Finding f = e.getValue();
            Decision d = decisions.find(e.getKey());
            List<Action> acts = f.actionable() && f.metric() != null
                    ? acted.getOrDefault(f.metric(), List.of()) : List.of();
            if (d == null) {
                d = opened(a, f, sent.get(e.getKey()), acts, skip.get(f.metric()), why, now);
                if (judged && why == null && acts.isEmpty() && needsDevice(f)) {
                    // 규칙은 켜지 않았다. 켤 기기는 판단 에이전트가 고른다(화면에는 "고르는 중" 로딩)
                    d = d.with(Step.CONTROL, Status.RUNNING, "오디 AI가 켤 기기를 고르는 중", now);
                    handIds.add(d.id());
                    handFindings.add(detect(a, f) + ". " + predict(a, f));
                }
                decisions.put(d);
                continue;
            }
            Decision next = d;
            if (rank(f.kind()) > rank(d.kind())) {
                next = next.kind(f.kind())
                        .with(Step.DETECT, Status.DONE, detect(a, f), now)
                        .with(Step.PREDICT, Status.DONE, predict(a, f), now);
            }
            Alert alert = sent.get(e.getKey());
            if (alert != null) {
                next = next.with(Step.NOTIFY, Status.DONE, notifyText(alert), now);
            }
            if (!acts.isEmpty()) {
                String before = d.step(Step.CONTROL).status() == Status.DONE ? d.step(Step.CONTROL).text() + ", " : "";
                next = next.with(Step.CONTROL, failed(acts) ? Status.FAILED : Status.DONE,
                        before + controlText(acts), now);
            }
            Duration open = Duration.between(d.openedAt(), now);
            if (open.compareTo(props.alertEvery()) >= 0) {
                next = next.with(Step.VERIFY, Status.FAILED,
                        "%d분째 그대로예요. 다시 볼게요".formatted(open.toMinutes()), now).closed(now);
            }
            if (!next.equals(d)) {
                decisions.put(next);
            }
        }
        if (!handIds.isEmpty()) {
            judge.ask(new Judge.Request(a.roomId(), a.name(), List.copyOf(handIds), List.copyOf(handFindings)), judgeListener);
        }
        if (a.offline()) {
            return; // 값이 안 오는 동안은 돌아왔는지 알 수 없다
        }
        for (Decision d : decisions.openIn(a.roomId())) {
            if (!seen.containsKey(d.key())) {
                Instant quiet = quietSince.computeIfAbsent(d.key(), k -> now);
                if (Duration.between(quiet, now).compareTo(props.settleOrZero()) < 0) {
                    continue; // 잠깐 사라진 것일 수 있다. settle 동안 계속 없어야 닫는다
                }
                quietSince.remove(d.key());
                long min = Math.max(1, Duration.between(d.openedAt(), quiet).toMinutes());
                String text = "SENSOR".equals(d.group()) || d.metric() == null
                        ? "센서가 다시 정상이에요 (%d분)".formatted(min)
                        : "%s 평소 범위로 돌아왔어요 (%d분 걸림)".formatted(label(d.metric()) + now(a, d.metric()), min);
                decisions.put(d.with(Step.VERIFY, Status.DONE, text, now).closed(now));
            }
        }
    }

    private Decision opened(RoomAnalysis a, Finding f, Alert alert, List<Action> acts, String skip, String why,
                            Instant now) {
        Map<Step, Decision.StepState> steps = new EnumMap<>(Step.class);
        steps.put(Step.DETECT, new Decision.StepState(Status.DONE, detect(a, f), now));
        boolean sensor = !f.actionable();
        steps.put(Step.PREDICT, sensor
                ? new Decision.StepState(Status.SKIPPED, "센서 문제라 예측하지 않아요", now)
                : new Decision.StepState(Status.DONE, predict(a, f), now));
        steps.put(Step.NOTIFY, alert != null
                ? new Decision.StepState(Status.DONE, notifyText(alert), now)
                : new Decision.StepState(Status.SKIPPED, "30분 안에 같은 알림을 보내서 쉬어요", now));
        Decision.StepState control;
        if (sensor) {
            control = new Decision.StepState(Status.SKIPPED, "센서 값이 이상할 땐 기기를 안 건드려요", now);
        } else if (!acts.isEmpty()) {
            control = new Decision.StepState(failed(acts) ? Status.FAILED : Status.DONE, controlText(acts), now);
        } else {
            control = new Decision.StepState(Status.SKIPPED, noControl(a.roomId(), f, skip, why), now);
        }
        steps.put(Step.CONTROL, control);
        steps.put(Step.VERIFY, new Decision.StepState(Status.RUNNING, sensor
                ? "센서 값이 다시 정상인지 보는 중"
                : "평소 범위로 돌아오는지 보는 중", now));
        return new Decision(UUID.randomUUID().toString(), a.roomId(), a.name(), f.metric(), group(f.kind()), f.kind(),
                now, null, steps);
    }

    /** 규칙이라면 기기를 켰을 이상인가: 온도(냉·난방), 습도 높음(제습), CO2 높음(환풍기) */
    private static boolean needsDevice(Finding f) {
        if (!f.actionable() || f.metric() == null) {
            return false;
        }
        boolean high = group(f.kind()).equals("HIGH");
        return f.metric() == Metric.TEMPERATURE || high;
    }

    // ---------- 판단 에이전트 결과 ----------

    private final Judge.Listener judgeListener = new Judge.Listener() {
        @Override
        public void progress(Judge.Request r, String text) {
            judgeUpdate(r, Status.RUNNING, text);
        }

        @Override
        public void done(Judge.Request r, List<String> did, String reason) {
            String what = did.isEmpty() ? "켤 기기 없음" : String.join(", ", did);
            String text = reason == null || reason.isBlank() ? what : what + " · " + reason.strip();
            judgeUpdate(r, did.isEmpty() ? Status.SKIPPED : Status.DONE, text);
        }

        @Override
        public void fallback(Judge.Request r, String why) {
            judgeFallback(r, why);
        }
    };

    private synchronized void judgeUpdate(Judge.Request r, Status status, String text) {
        Instant now = clock.instant();
        for (String id : r.decisionIds()) {
            Decision d = decisions.byId(id);
            if (d != null && d.step(Step.CONTROL).status() == Status.RUNNING) {
                decisions.put(d.with(Step.CONTROL, status, text, now));
            }
        }
    }

    /** 맡기지 못했으면 규칙이 지금 그 방을 다시 보고 켠다 */
    private synchronized void judgeFallback(Judge.Request r, String why) {
        Instant now = clock.instant();
        Map<Metric, List<Action>> acted = new EnumMap<>(Metric.class);
        Map<Metric, String> skip = new EnumMap<>(Metric.class);
        String stop;
        try {
            stop = control(analyzer.analyze(store.view(r.roomId())), acted, skip, true);
        } catch (RuntimeException e) {
            log.warn("방 {} 규칙 대신 켜기 실패: {}", r.roomId(), e.getClass().getSimpleName());
            stop = "기기를 움직이지 못했어요";
        }
        for (String id : r.decisionIds()) {
            Decision d = decisions.byId(id);
            if (d == null || d.step(Step.CONTROL).status() != Status.RUNNING) {
                continue;
            }
            List<Action> acts = d.metric() == null ? List.of() : acted.getOrDefault(d.metric(), List.of());
            String head = "규칙으로 판단(" + why + "): ";
            if (acts.isEmpty()) {
                String none = stop != null ? stop : skip.getOrDefault(d.metric(), "켤 기기 없음");
                decisions.put(d.with(Step.CONTROL, Status.SKIPPED, head + none, now));
            } else {
                decisions.put(d.with(Step.CONTROL, failed(acts) ? Status.FAILED : Status.DONE, head + controlText(acts), now));
            }
        }
    }

    private String noControl(int room, Finding f, String skip, String why) {
        if (why != null) {
            return why;
        }
        if (skip != null) {
            return skip;
        }
        boolean high = group(f.kind()).equals("HIGH");
        return switch (f.metric()) {
            case TEMPERATURE -> facade.has(room, ApplianceKind.AIRCON) ? "에어컨이 이미 맞춰져 있어요" : "이 방엔 에어컨이 없어요";
            case HUMIDITY -> !high ? "가습기가 없어 알림만 해요"
                    : facade.has(room, ApplianceKind.AIRCON) ? "에어컨이 이미 맞춰져 있어요" : "이 방엔 에어컨이 없어요";
            case CO2 -> !high ? "CO2가 낮은 건 조치할 필요 없어요"
                    : facade.has(room, ApplianceKind.VENTILATOR) || facade.has(room, ApplianceKind.PURIFIER)
                    ? "기기가 이미 켜져 있어요" : "이 방엔 기기가 없어 환기 알림만 해요";
        };
    }

    private String notifyText(Alert alert) {
        if (alert.audience() == Alert.Audience.ADMIN) {
            return "관리자 화면에 띄웠어요";
        }
        return reachesStudents ? "방 사용자에게 알림 보냈어요" : "방 사용자 알림을 만들었어요 (학생 알림함 연결 전)";
    }

    private static String detect(RoomAnalysis a, Finding f) {
        if (f.metric() == null) {
            return f.message();
        }
        var m = a.metrics().get(f.metric());
        if (m == null || m.latest() == null) {
            return f.message();
        }
        String v = label(f.metric()) + " " + f.metric().format(m.latest().value()) + f.metric().unit();
        Double mean = m.baseline().mean();
        if (!f.actionable() || mean == null) {
            return v;
        }
        return "%s (이 방 평소 %s%s)".formatted(v, f.metric().format(mean), f.metric().unit());
    }

    /** 이미 넘은 값이면 탐지 문장을 되풀이하지 않고, 예측선 끝(30분 뒤) 값과 방향을 말한다 */
    private static String predict(RoomAnalysis a, Finding f) {
        boolean over = f.kind() == Finding.Kind.OVER;
        if (!over && f.kind() != Finding.Kind.UNDER) {
            return f.message();
        }
        var m = a.metrics().get(f.metric());
        if (m == null || m.latest() == null || m.forecast() == null || m.forecast().points().isEmpty()) {
            return f.message();
        }
        Sample end = m.forecast().points().get(m.forecast().points().size() - 1);
        double diff = end.value() - m.latest().value();
        long min = Math.max(1, Duration.between(m.latest().at(), end.at()).toMinutes());
        String way = f.metric().format(end.value()).equals(f.metric().format(m.latest().value())) ? "이대로 머물 것 같아요"
                : (diff > 0) == over ? (over ? "더 오를 것 같아요" : "더 내려갈 것 같아요")
                : (over ? "조금씩 내려올 것 같아요" : "조금씩 올라올 것 같아요");
        return "그냥 두면 %d분 뒤 %s%s, %s".formatted(min, f.metric().format(end.value()), f.metric().unit(), way);
    }

    private static String now(RoomAnalysis a, Metric metric) {
        var m = a.metrics().get(metric);
        return m == null || m.latest() == null ? "" : " " + metric.format(m.latest().value()) + metric.unit() + ",";
    }

    private static String controlText(List<Action> acts) {
        List<String> parts = new ArrayList<>();
        for (Action x : acts) {
            String name = switch (x.kind()) {
                case AIRCON -> "에어컨";
                case PURIFIER -> "공기청정기";
                case VENTILATOR -> "환풍기";
            };
            String what;
            if (x.command().power() == ApplianceCommand.Power.OFF) {
                what = "끔";
            } else if (x.command().mode() == null) {
                what = "켬";
            } else {
                what = switch (x.command().mode()) {
                    case COOL -> "냉방 " + x.command().targetC() + "°C";
                    case HEAT -> "난방 " + x.command().targetC() + "°C";
                    case DRY -> "제습";
                };
            }
            parts.add(name + " " + what + (x.ok() ? "" : " 실패(1분 뒤 다시)"));
        }
        return String.join(", ", parts);
    }

    private static boolean failed(List<Action> acts) {
        return acts.stream().anyMatch(x -> !x.ok());
    }

    static String group(Finding.Kind k) {
        return switch (k) {
            case OVER, RISING_SOON -> "HIGH";
            case UNDER, FALLING_SOON -> "LOW";
            default -> "SENSOR";
        };
    }

    /** 곧 넘음 → 넘음으로 커지면 탐지·예측 문구를 갈아 끼운다 */
    private static int rank(Finding.Kind k) {
        return switch (k) {
            case OVER, UNDER -> 2;
            case RISING_SOON, FALLING_SOON -> 1;
            default -> 0;
        };
    }

    private static String label(Metric m) {
        return switch (m) {
            case CO2 -> "CO2";
            case TEMPERATURE -> "온도";
            case HUMIDITY -> "습도";
        };
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
}
