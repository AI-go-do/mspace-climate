package kr.ac.chosun.mspace.climate.domain;

import java.time.Instant;
import java.util.List;

/**
 * 예측 포트. 지금은 최근 추세(TrendForecaster). 젯슨 시계열 모델(Chronos-2, 실습 모델)이 생기면 같은 모양의 어댑터를 더한다.
 */
public interface Forecaster {

    Forecast forecast(List<Sample> recent, Baseline baseline, Instant now);
}
