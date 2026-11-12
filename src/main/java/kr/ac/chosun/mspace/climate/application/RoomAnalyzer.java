package kr.ac.chosun.mspace.climate.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.climate.domain.AnomalyDetector;
import kr.ac.chosun.mspace.climate.domain.Baseline;
import kr.ac.chosun.mspace.climate.domain.BaselineCalculator;
import kr.ac.chosun.mspace.climate.domain.Finding;
import kr.ac.chosun.mspace.climate.domain.Forecast;
import kr.ac.chosun.mspace.climate.domain.Forecaster;
import kr.ac.chosun.mspace.climate.domain.Metric;
import kr.ac.chosun.mspace.climate.domain.Sample;

/** 방 기록 → 기준선·예측·이상 (기기는 안 건드림. 화면 조회와 오디 판단이 같이 쓴다) */
public class RoomAnalyzer {

    private final ClimateProperties props;
    private final BaselineCalculator baselines;
    private final Forecaster forecaster;
    private final AnomalyDetector detector;
    private final Clock clock;

    public RoomAnalyzer(ClimateProperties props, BaselineCalculator baselines, Forecaster forecaster,
                        AnomalyDetector detector, Clock clock) {
        this.props = props;
        this.baselines = baselines;
        this.forecaster = forecaster;
        this.detector = detector;
        this.clock = clock;
    }

    public RoomAnalysis analyze(ClimateStore.RoomView room) {
        Instant now = clock.instant();
        Map<Metric, RoomAnalysis.MetricAnalysis> metrics = new EnumMap<>(Metric.class);
        List<Finding> all = new ArrayList<>();
        boolean offline = room.envAt() == null || room.envAt().plus(props.offline()).isBefore(now);
        for (Metric m : Metric.values()) {
            List<Sample> h = room.history().getOrDefault(m, List.of());
            Baseline b = baselines.baseline(h, now, props.rules().get(m));
            Sample latest = h.isEmpty() ? null : h.get(h.size() - 1);
            List<Finding> found = List.of();
            Forecast f = Forecast.none();
            if (!offline && latest != null) {
                f = forecaster.forecast(h, b, now);
                found = detector.detect(m, h, b, f, props.rules().get(m), now);
            }
            metrics.put(m, new RoomAnalysis.MetricAnalysis(latest, b, f, found));
            all.addAll(found);
        }
        if (offline && room.envAt() != null) {
            all.add(0, new Finding(Finding.Kind.OFFLINE, null,
                    "공기 센서 값이 %d분 넘게 안 들어와요".formatted(props.offline().toMinutes()), null));
        }
        return new RoomAnalysis(room.roomId(), room.name(), room.occupied(), room.occupiedSince(), room.auto(), offline,
                room.envAt(), metrics, all);
    }
}
