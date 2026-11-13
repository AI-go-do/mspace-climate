package kr.ac.chosun.mspace.climate.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.climate.domain.Baseline;
import kr.ac.chosun.mspace.climate.domain.Forecast;
import kr.ac.chosun.mspace.climate.domain.Metric;
import kr.ac.chosun.mspace.climate.domain.Sample;
import kr.ac.chosun.mspace.climate.domain.TrendForecaster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GruForecasterTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Instant NOW = Instant.parse("2026-11-12T06:02:30Z"); // 15:02:30 KST
    /** 실습 저장소 Python(forecast.features.calendar_features + onnxruntime)으로 같은 입력을 넣어 얻은 값 */
    static final double[] PY_CO2 = {9.217, 17.149, 23.958, 28.497, 35.934, 38.627, 41.805, 43.434, 50.316, 51.342, 57.803, 59.551};
    static final double[] PY_TEMP = {0.0351, 0.1021, 0.1334, 0.1754, 0.2068, 0.2381, 0.2624, 0.2943, 0.3305, 0.3602, 0.3786, 0.4093};

    final TrendForecaster trend = new TrendForecaster(Duration.ofMinutes(30), 5);
    final GruForecaster gru = GruForecaster.load(getClass().getClassLoader(), trend, SEOUL);

    /** 3시간 1분 값: CO2 600에서 1분에 2ppm, 온도 22°C에서 1분에 0.01°C, 습도 45% */
    static Map<Metric, List<Sample>> rising(int minutes, int skipFrom, int skipTo) {
        Map<Metric, List<Sample>> h = new EnumMap<>(Metric.class);
        for (Metric m : Metric.values()) {
            h.put(m, new ArrayList<>());
        }
        Instant start = NOW.truncatedTo(java.time.temporal.ChronoUnit.MINUTES).minus(Duration.ofMinutes(minutes));
        for (int i = 0; i < minutes; i++) {
            if (i >= skipFrom && i < skipTo) {
                continue;
            }
            Instant at = start.plus(Duration.ofMinutes(i));
            h.get(Metric.CO2).add(new Sample(at, 600 + 2.0 * i));
            h.get(Metric.TEMPERATURE).add(new Sample(at, 22 + 0.01 * i));
            h.get(Metric.HUMIDITY).add(new Sample(at, 45));
        }
        return h;
    }

    @Test
    @DisplayName("실습 모델 파일을 읽고, 같은 입력이면 실습 Python 서비스와 같은 예측을 낸다(0.05ppm·0.001°C 안)")
    void sameAsPython() throws Exception {
        assertThat(gru).isNotNull();
        double[] co2 = gru.predict(rising(180, -1, -1), NOW, 0);
        double[] temp = gru.predict(rising(180, -1, -1), NOW, 1);
        for (int k = 0; k < 12; k++) {
            assertThat(co2[k]).isCloseTo(PY_CO2[k], within(0.05));
            assertThat(temp[k]).isCloseTo(PY_TEMP[k], within(0.001));
        }
    }

    @Test
    @DisplayName("CO2가 오르는 중이면 1000ppm에 닿는 분을 내고 GRU라고 밝힌다. 습도는 직선(TREND)으로")
    void etaAndFallbackForHumidity() {
        Map<Metric, List<Sample>> h = rising(180, -1, -1);
        // 지금 958ppm, 모델은 60분 동안 약 +60ppm → 1100 경계에는 안 닿음
        Baseline b = new Baseline(700.0, 100.0, null, 1100.0, 100, false, "HOUR");
        Forecast f = gru.forecast(Metric.CO2, h, b, NOW);
        assertThat(f.method()).isEqualTo(Forecast.GRU);
        assertThat(f.towards()).isNull();
        Baseline low = new Baseline(700.0, 100.0, null, 980.0, 100, false, "HOUR");
        Forecast g = gru.forecast(Metric.CO2, h, low, NOW);
        assertThat(g.towards()).isEqualTo(Forecast.Boundary.UPPER);
        assertThat(g.etaMinutes()).isBetween(1, 30);
        assertThat(g.points()).isNotEmpty();
        assertThat(gru.forecast(Metric.HUMIDITY, h, b, NOW).method()).isEqualTo(Forecast.TREND);
    }

    @Test
    @DisplayName("2시간 창에 값이 모자라면(75% 미만) 모델 대신 직선으로")
    void tooFewFallsBack() {
        // 최근 2시간 중 앞 40분이 비면 창의 약 67%만 있음 → 직선
        Map<Metric, List<Sample>> h = rising(180, 60, 100);
        Baseline b = new Baseline(700.0, 100.0, null, 1000.0, 100, false, "HOUR");
        assertThat(gru.forecast(Metric.CO2, h, b, NOW).method()).isEqualTo(Forecast.TREND);
        // 15분 이하 빈칸은 보간해서 모델을 쓴다
        Map<Metric, List<Sample>> small = rising(180, 120, 130);
        assertThat(gru.forecast(Metric.CO2, small, b, NOW).method()).isEqualTo(Forecast.GRU);
    }

    @Test
    @DisplayName("1분 값 하나가 튀어도 예측선은 최근 3분 평균에서 출발해 덜 뛴다")
    void anchorSmoothsOneSpike() {
        // given: 오르던 CO2의 마지막 1분 값만 +60ppm 튐(센서 오차)
        Map<Metric, List<Sample>> h = rising(180, -1, -1);
        List<Sample> co2 = h.get(Metric.CO2);
        Sample last = co2.remove(co2.size() - 1);
        co2.add(new Sample(last.at(), last.value() + 60));
        GruForecaster one = GruForecaster.load(getClass().getClassLoader(), trend, SEOUL, 1);
        Baseline b = new Baseline(700.0, 100.0, null, 1100.0, 100, false, "HOUR");
        // when
        double spiked = one.forecast(Metric.CO2, h, b, NOW).points().get(0).value();
        double smooth = gru.forecast(Metric.CO2, h, b, NOW).points().get(0).value();
        // then: 3분 창(05:59:30 뒤)의 06:00·06:01 두 값 평균에서 출발 → 튄 60ppm 중 절반 정도만 따라감
        assertThat(spiked - smooth).isCloseTo(31, within(0.5));
    }
}
