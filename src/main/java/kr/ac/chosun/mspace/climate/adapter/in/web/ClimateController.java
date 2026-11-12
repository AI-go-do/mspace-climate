package kr.ac.chosun.mspace.climate.adapter.in.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.climate.appliance.ApplianceCommand;
import kr.ac.chosun.mspace.climate.appliance.ApplianceState;
import kr.ac.chosun.mspace.climate.appliance.ClimateFacade;
import kr.ac.chosun.mspace.climate.application.ReadingIntake;
import kr.ac.chosun.mspace.climate.application.AlertLog;
import kr.ac.chosun.mspace.climate.application.ClimateStore;
import kr.ac.chosun.mspace.climate.application.Decision;
import kr.ac.chosun.mspace.climate.application.DecisionBook;
import kr.ac.chosun.mspace.climate.application.RoomAnalysis;
import kr.ac.chosun.mspace.climate.application.RoomAnalyzer;
import kr.ac.chosun.mspace.climate.auth.AdminChecker;
import kr.ac.chosun.mspace.climate.auth.ApiException;
import kr.ac.chosun.mspace.climate.auth.JwtVerifier;
import kr.ac.chosun.mspace.climate.domain.Baseline;
import kr.ac.chosun.mspace.climate.domain.BaselineCalculator;
import kr.ac.chosun.mspace.climate.domain.Finding;
import kr.ac.chosun.mspace.climate.domain.Metric;
import kr.ac.chosun.mspace.climate.domain.Sample;
import kr.ac.chosun.mspace.climate.application.ClimateProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 화면·학생 화면이 보는 방 공기 API. 보기는 로그인한 누구나(방 공기는 개인정보가 아님), 기기 조작·자동 끔은 관리자만.
 * <ul>
 *   <li>GET /api/climate/rooms: 방마다 지금 값·이 방 기준선·예측·이상·기기 상태</li>
 *   <li>GET /api/climate/rooms/{roomId}/series?minutes=180: 그래프용 1분 값 + 기준선 띠 + 예측 점선</li>
 *   <li>GET /api/climate/log?limit=50: 오디 조치 기록·알림</li>
 *   <li>POST /api/climate/rooms/{roomId}/appliances/{applianceId}/commands: 관리자 손 조작(30분 동안 오디가 안 건드림)</li>
 *   <li>PUT /api/climate/rooms/{roomId}/auto: 오디 자동 제어 켬/끔</li>
 * </ul>
 */
@RestController
class ClimateController {

    static final int MAX_MINUTES = 7 * 24 * 60;

    private final ClimateStore store;
    private final RoomAnalyzer analyzer;
    private final ClimateFacade facade;
    private final AlertLog alerts;
    private final JwtVerifier jwt;
    private final AdminChecker admins;
    private final BaselineCalculator baselines;
    private final ClimateProperties props;
    private final java.time.Clock clock;
    private final LiveFeed live;
    private final DecisionBook decisions;
    private final ReadingIntake intake;

    ClimateController(ClimateStore store, RoomAnalyzer analyzer, ClimateFacade facade, AlertLog alerts, JwtVerifier jwt,
                      AdminChecker admins, ClimateProperties props, java.time.Clock clock, LiveFeed live,
                      DecisionBook decisions, ReadingIntake intake) {
        this.intake = intake;
        this.live = live;
        this.decisions = decisions;
        decisions.listen(d -> live.publish("decision", DecisionView.of(d)));
        this.store = store;
        this.analyzer = analyzer;
        this.facade = facade;
        this.alerts = alerts;
        this.jwt = jwt;
        this.admins = admins;
        this.props = props;
        this.clock = clock;
        this.baselines = new BaselineCalculator(java.time.ZoneId.of("Asia/Seoul"), props.k(), props.minSamples());
    }

    @GetMapping("/api/climate/rooms")
    Map<String, Object> rooms(@RequestHeader(name = "Authorization", required = false) String auth) {
        jwt.memberId(auth);
        List<RoomView> out = new ArrayList<>();
        for (int id : store.roomIds()) {
            RoomAnalysis a = analyzer.analyze(store.view(id));
            String source = intake.simRooms().contains(id) ? "SIM"
                    : intake.directRooms().contains(id) && !intake.replayRooms().contains(id) ? "REAL"
                    : intake.replayRooms().contains(id) ? "REPLAY"
                    : props.derive() != null && props.derive().derived(id) ? "DERIVED" : "REAL";
            out.add(RoomView.of(a, facade.states(id), source, intake.people(id)));
        }
        return Map.of("now", Times.kst(clock.instant()), "rooms", out);
    }

    /**
     * 실시간 연결(SSE). 브라우저 EventSource는 헤더를 못 붙여서 화면은 fetch로 읽는다.
     * X-Accel-Buffering: no = nginx가 쌓아 두지 말고 바로 흘려보내라는 뜻
     */
    @GetMapping(path = "/api/climate/stream", produces = "text/event-stream")
    org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.SseEmitter> stream(
            @RequestHeader(name = "Authorization", required = false) String auth) {
        jwt.memberId(auth);
        return org.springframework.http.ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-cache")
                .body(live.open());
    }

    @GetMapping("/api/climate/rooms/{roomId}/series")
    Map<String, Object> series(@RequestHeader(name = "Authorization", required = false) String auth,
                               @PathVariable int roomId, @RequestParam(defaultValue = "180") int minutes) {
        jwt.memberId(auth);
        if (minutes < 1 || minutes > MAX_MINUTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "minutes는 1–" + MAX_MINUTES + " 사이여야 해요");
        }
        var view = store.view(roomId);
        if (view == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "없는 방이에요");
        }
        RoomAnalysis a = analyzer.analyze(view);
        Instant from = clock.instant().minus(Duration.ofMinutes(minutes));
        Map<Metric, SeriesView> metrics = new EnumMap<>(Metric.class);
        for (Metric m : Metric.values()) {
            List<Sample> h = view.history().getOrDefault(m, List.of());
            List<Point> pts = new ArrayList<>();
            // 기준선 띠: 시간대마다 다르니 그 시각 기준으로 (1시간에 한 번만 계산)
            Map<Integer, Baseline> byHour = new java.util.HashMap<>();
            for (Sample s : h) {
                if (s.at().isBefore(from)) {
                    continue;
                }
                int hour = s.at().atZone(java.time.ZoneId.of("Asia/Seoul")).getHour();
                Baseline b = byHour.computeIfAbsent(hour, x -> baselines.baseline(h, s.at(), props.rules().get(m)));
                pts.add(new Point(Times.kst(s.at()), s.value(), b.lower(), b.upper()));
            }
            var ma = a.metrics().get(m);
            List<Point> fc = ma.forecast().points().stream()
                    .map(s -> new Point(Times.kst(s.at()), s.value(), null, null)).toList();
            metrics.put(m, new SeriesView(m.unit(), pts, fc, ma.baseline()));
        }
        return Map.of("roomId", roomId, "metrics", metrics);
    }

    /** 오디 판단 카드 (탐지 → 예측 → 알림 → 제어 → 다시 확인). 새것부터. roomId를 주면 그 방만 */
    @GetMapping("/api/climate/decisions")
    Map<String, Object> decisions(@RequestHeader(name = "Authorization", required = false) String auth,
                                  @RequestParam(defaultValue = "20") int limit,
                                  @RequestParam(required = false) Integer roomId) {
        jwt.memberId(auth);
        int n = Math.max(1, Math.min(limit, 200));
        var list = decisions.recent(200).stream()
                .filter(d -> roomId == null || d.roomId() == roomId)
                .limit(n).map(DecisionView::of).toList();
        return Map.of("decisions", list);
    }

    @GetMapping("/api/climate/log")
    Map<String, Object> log(@RequestHeader(name = "Authorization", required = false) String auth,
                            @RequestParam(defaultValue = "50") int limit) {
        jwt.memberId(auth);
        int n = Math.max(1, Math.min(limit, 500));
        var actions = facade.recentActions(n).stream().map(ActionView::of).toList();
        var alertViews = alerts.recent(n).stream().map(x -> new AlertView(Times.kst(x.at()), x.roomId(),
                x.audience().name(), x.metric() == null ? null : x.metric().name(), x.kind().name(), x.message(),
                x.etaMinutes())).toList();
        return Map.of("actions", actions, "alerts", alertViews);
    }

    @PostMapping("/api/climate/rooms/{roomId}/appliances/{applianceId}/commands")
    ApplianceView command(@RequestHeader(name = "Authorization", required = false) String auth,
                          @PathVariable int roomId, @PathVariable String applianceId,
                          @RequestBody ApplianceCommand body) {
        long member = jwt.memberId(auth);
        admins.requireAdmin(member, auth);
        var a = facade.find(applianceId).filter(x -> x.roomId() == roomId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPLIANCE_NOT_FOUND", "그 방에 없는 기기예요"));
        if (body == null || body.power() == null
                || (body.targetC() != null && (body.targetC() < 16 || body.targetC() > 30))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "power는 ON/OFF, 온도는 16–30도예요");
        }
        ApplianceView v = ApplianceView.of(facade.command(a.id(), body, "ADMIN", "관리자가 직접 바꿈"));
        live.publish("tick", Map.of("roomId", roomId));
        return v;
    }

    @PutMapping("/api/climate/rooms/{roomId}/auto")
    Map<String, Object> auto(@RequestHeader(name = "Authorization", required = false) String auth,
                             @PathVariable int roomId, @RequestBody Map<String, Boolean> body) {
        long member = jwt.memberId(auth);
        admins.requireAdmin(member, auth);
        if (store.view(roomId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "없는 방이에요");
        }
        Boolean on = body == null ? null : body.get("enabled");
        if (on == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "enabled(true/false)가 필요해요");
        }
        store.setAuto(roomId, on);
        return Map.of("roomId", roomId, "auto", on);
    }

    // ---------- 응답 모양 ----------

    /** source: REAL(실제 센서) / DERIVED(실습실 값으로 만든 값) / REPLAY(멈춘 센서 대신 실측 녹화 재생) / SIM(시뮬레이션) */
    record RoomView(int roomId, String roomName, String source, Boolean occupied, boolean auto, boolean offline,
                    String envAt, Map<Metric, MetricView> metrics, List<FindingView> findings,
                    List<ApplianceView> appliances, String occupiedSince, Integer people) {

        /** people: 시뮬레이션 방만 인원(실제 방은 센서가 인원을 못 세서 null). occupiedSince: 지금 재실 상태가 시작된 시각 */
        static RoomView of(RoomAnalysis a, List<ApplianceState> states, String source, Integer people) {
            Map<Metric, MetricView> m = new EnumMap<>(Metric.class);
            a.metrics().forEach((k, v) -> m.put(k, new MetricView(k.unit(),
                    v.latest() == null ? null : v.latest().value(),
                    v.latest() == null ? null : Times.kst(v.latest().at()), v.baseline(),
                    v.forecast().slopePerMinute(), v.forecast().etaMinutes(),
                    v.forecast().towards() == null ? null : v.forecast().towards().name(),
                    status(v))));
            return new RoomView(a.roomId(), a.name(), source, a.occupied(), a.auto(), a.offline(), Times.kst(a.envAt()), m,
                    a.findings().stream().map(FindingView::of).toList(),
                    states.stream().map(ApplianceView::of).toList(), Times.kst(a.occupiedSince()), people);
        }

        /** 화면 색: OK / SOON(곧 넘음) / OVER(넘음) / SENSOR(센서 이상) / NONE(값 없음) */
        static String status(RoomAnalysis.MetricAnalysis v) {
            if (v.latest() == null) {
                return "NONE";
            }
            if (v.has(Finding.Kind.OVER, Finding.Kind.UNDER)) {
                return "OVER";
            }
            if (v.has(Finding.Kind.RISING_SOON, Finding.Kind.FALLING_SOON)) {
                return "SOON";
            }
            if (v.has(Finding.Kind.SPIKE, Finding.Kind.STUCK)) {
                return "SENSOR";
            }
            return "OK";
        }
    }

    record MetricView(String unit, Double value, String at, Baseline baseline, double trendPerMinute,
                      Integer etaMinutes, String towards, String status) {
    }

    record FindingView(String kind, String metric, String message, Integer etaMinutes) {
        static FindingView of(Finding f) {
            return new FindingView(f.kind().name(), f.metric() == null ? null : f.metric().name(), f.message(),
                    f.etaMinutes());
        }
    }

    record ApplianceView(String applianceId, int roomId, String kind, String brand, String adapter, String power,
                         String mode, Integer targetC, String changedAt, String by) {
        static ApplianceView of(ApplianceState s) {
            return new ApplianceView(s.applianceId(), s.roomId(), s.kind().name(), s.brand(), s.adapter(),
                    s.power().name(), s.mode() == null ? null : s.mode().name(), s.targetC(), Times.kst(s.changedAt()),
                    s.by());
        }
    }

    record ActionView(String at, int roomId, String applianceId, String kind, String power, String mode,
                      Integer targetC, String by, String reason, boolean ok) {
        static ActionView of(ClimateFacade.Action x) {
            return new ActionView(Times.kst(x.at()), x.roomId(), x.applianceId(), x.kind().name(),
                    x.command().power().name(), x.command().mode() == null ? null : x.command().mode().name(),
                    x.command().targetC(), x.by(), x.reason(), x.ok());
        }
    }

    /** 단계 key: DETECT 탐지 / PREDICT 예측 / NOTIFY 알림 / CONTROL 제어 / VERIFY 다시 확인. status: RUNNING·DONE·SKIPPED·FAILED */
    record DecisionView(String id, int roomId, String roomName, String metric, String group, String kind,
                        String openedAt, String closedAt, boolean open, List<StepView> steps) {
        static DecisionView of(Decision d) {
            List<StepView> steps = new ArrayList<>();
            for (Decision.Step s : Decision.Step.values()) {
                Decision.StepState st = d.step(s);
                if (st != null) {
                    steps.add(new StepView(s.name(), st.status().name(), st.text(), Times.kst(st.at())));
                }
            }
            return new DecisionView(d.id(), d.roomId(), d.roomName(), d.metric() == null ? null : d.metric().name(),
                    d.group(), d.kind().name(), Times.kst(d.openedAt()),
                    d.closedAt() == null ? null : Times.kst(d.closedAt()), d.open(), steps);
        }
    }

    record StepView(String key, String status, String text, String at) {
    }

    record AlertView(String at, int roomId, String audience, String metric, String kind, String message,
                     Integer etaMinutes) {
    }

    record Point(String at, double value, Double lower, Double upper) {
    }

    record SeriesView(String unit, List<Point> points, List<Point> forecast, Baseline baseline) {
    }
}
