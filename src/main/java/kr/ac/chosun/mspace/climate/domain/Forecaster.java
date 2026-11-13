package kr.ac.chosun.mspace.climate.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 예측 포트. 기본은 최근 추세(TrendForecaster). 실습 시계열 모델(GRU)은 같은 포트의 어댑터(adapter.out.model)로,
 * 모델이 못 할 때(값 부족·습도·모델 없음)는 추세로 돌아간다.
 */
public interface Forecaster {

    Forecast forecast(List<Sample> recent, Baseline baseline, Instant now);

    /** 여러 지표를 함께 보는 모델용(GRU는 CO2·온도·습도를 같이 읽는다). 기본은 그 지표 값만 본다 */
    default Forecast forecast(Metric metric, Map<Metric, List<Sample>> history, Baseline baseline, Instant now) {
        return forecast(history.getOrDefault(metric, List.of()), baseline, now);
    }
}
