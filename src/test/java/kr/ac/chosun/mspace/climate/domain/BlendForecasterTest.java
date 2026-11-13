package kr.ac.chosun.mspace.climate.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlendForecasterTest {

    static final Instant NOW = Instant.parse("2026-11-13T00:30:00Z");
    static final Baseline LIMIT_1000 = new Baseline(700.0, 100.0, null, 1000.0, 100, false, "HOUR");

    /** 지금 900ppm. 모델은 10분 뒤 1060(1분 +16), 직선은 1분 +2 */
    static final Forecaster MODEL = new Forecaster() {
        @Override
        public Forecast forecast(List<Sample> recent, Baseline b, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Forecast forecast(Metric m, Map<Metric, List<Sample>> h, Baseline b, Instant now) {
            return new Forecast(16, 7, Forecast.Boundary.UPPER,
                    List.of(new Sample(now.plus(Duration.ofMinutes(5)), 980), new Sample(now.plus(Duration.ofMinutes(10)), 1060)),
                    Forecast.GRU);
        }
    };
    static final Forecaster TREND = (recent, b, now) -> new Forecast(2, null, null, List.of());

    static Map<Metric, List<Sample>> history() {
        return Map.of(Metric.CO2, List.of(new Sample(NOW.minusSeconds(60), 900)));
    }

    @Test
    @DisplayName("GRU 예측선에 직선을 25% 섞고, 섞은 선으로 1000ppm에 닿는 분을 다시 낸다")
    void blendsAndRecomputesEta() {
        // given
        BlendForecaster f = new BlendForecaster(MODEL, TREND, 0.25);
        // when
        Forecast out = f.forecast(Metric.CO2, history(), LIMIT_1000, NOW);
        // then: 5분 0.75×980 + 0.25×910 = 962.5, 10분 0.75×1060 + 0.25×920 = 1025
        assertThat(out.points().get(0).value()).isCloseTo(962.5, within(0.01));
        assertThat(out.points().get(1).value()).isCloseTo(1025, within(0.01));
        // 962.5 → 1025 사이에서 1000에 닿는 분: 5 + 37.5/62.5×5 = 8분
        assertThat(out.etaMinutes()).isEqualTo(8);
        assertThat(out.method()).isEqualTo(Forecast.GRU);
    }

    @Test
    @DisplayName("모델이 직선으로 돌아간 예측은 섞지 않고 그대로 둔다")
    void keepsTrendFallback() {
        // given
        Forecast fallback = new Forecast(3, 20, Forecast.Boundary.UPPER, List.of());
        Forecaster model = (recent, b, now) -> fallback;
        // when
        Forecast out = new BlendForecaster(model, TREND, 0.25).forecast(Metric.CO2, history(), LIMIT_1000, NOW);
        // then
        assertThat(out).isSameAs(fallback);
    }
}
