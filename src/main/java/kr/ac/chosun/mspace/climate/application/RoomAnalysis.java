package kr.ac.chosun.mspace.climate.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.climate.domain.Baseline;
import kr.ac.chosun.mspace.climate.domain.Finding;
import kr.ac.chosun.mspace.climate.domain.Forecast;
import kr.ac.chosun.mspace.climate.domain.Metric;
import kr.ac.chosun.mspace.climate.domain.Sample;

/** 방 하나를 지금 본 결과: 지표마다 최신 값·기준선·예측·이상, 방 전체 이상(끊김) */
public record RoomAnalysis(int roomId, String name, Boolean occupied, Instant occupiedSince, boolean auto,
                           boolean offline, Instant envAt, Map<Metric, MetricAnalysis> metrics,
                           List<Finding> findings) {

    public record MetricAnalysis(Sample latest, Baseline baseline, Forecast forecast, List<Finding> findings) {

        public boolean has(Finding.Kind... kinds) {
            for (Finding f : findings) {
                for (Finding.Kind k : kinds) {
                    if (f.kind() == k) {
                        return true;
                    }
                }
            }
            return false;
        }

        public Finding first(Finding.Kind... kinds) {
            for (Finding f : findings) {
                for (Finding.Kind k : kinds) {
                    if (f.kind() == k) {
                        return f;
                    }
                }
            }
            return null;
        }
    }
}
