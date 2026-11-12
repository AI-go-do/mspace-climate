package kr.ac.chosun.mspace.climate.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrendAndAnomalyTest {

    static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");
    static final MetricRule CO2 = new MetricRule(null, 1000.0, 150, 400, false);
    static final Baseline CO2_BASE = new Baseline(600.0, 60.0, null, 1000.0, 500, false, "HOUR");

    final TrendForecaster forecaster = new TrendForecaster(Duration.ofMinutes(30), 5);
    final AnomalyDetector detector = new AnomalyDetector(Duration.ofMinutes(15), Duration.ofMinutes(20));

    /** 지난 minutes분 동안 start에서 1분에 perMinute씩 */
    static List<Sample> line(int minutes, double start, double perMinute) {
        List<Sample> out = new ArrayList<>();
        for (int i = minutes; i >= 0; i--) {
            out.add(new Sample(NOW.minus(Duration.ofMinutes(i)), start + perMinute * (minutes - i)));
        }
        return out;
    }

    @Test
    @DisplayName("1분에 10ppm씩 올라 880ppm이면 12분 뒤 1000ppm에 닿는다고 미리 알린다 (넘기 전에)")
    void risingSoonBeforeThreshold() {
        // given: 20분 동안 680 → 880
        List<Sample> h = line(20, 680, 10);

        // when
        Forecast f = forecaster.forecast(h, CO2_BASE, NOW);
        List<Finding> found = detector.detect(Metric.CO2, h, CO2_BASE, f, CO2, NOW);

        // then
        assertThat(f.slopePerMinute()).isEqualTo(10.0);
        assertThat(f.etaMinutes()).isEqualTo(12);
        assertThat(found).extracting(Finding::kind).containsExactly(Finding.Kind.RISING_SOON);
        assertThat(found.get(0).message()).contains("12분 뒤");
        assertThat(f.points()).hasSize(6);
    }

    @Test
    @DisplayName("천천히 오르면(30분 넘게 남음) 아직 알리지 않는다")
    void slowRiseNoAlert() {
        List<Sample> h = line(20, 700, 2);   // 740, 1분 2ppm → 130분 뒤
        Forecast f = forecaster.forecast(h, CO2_BASE, NOW);
        assertThat(detector.detect(Metric.CO2, h, CO2_BASE, f, CO2, NOW)).isEmpty();
    }

    @Test
    @DisplayName("이미 경계를 넘었으면 OVER")
    void over() {
        List<Sample> h = line(10, 1000, 5);
        Forecast f = forecaster.forecast(h, CO2_BASE, NOW);
        assertThat(detector.detect(Metric.CO2, h, CO2_BASE, f, CO2, NOW)).extracting(Finding::kind)
                .containsExactly(Finding.Kind.OVER);
    }

    @Test
    @DisplayName("값이 20분 동안 하나도 안 바뀌면 센서 멈춤 후보(STUCK)")
    void stuck() {
        List<Sample> h = line(25, 650, 0);
        Forecast f = forecaster.forecast(h, CO2_BASE, NOW);
        assertThat(detector.detect(Metric.CO2, h, CO2_BASE, f, CO2, NOW)).extracting(Finding::kind)
                .containsExactly(Finding.Kind.STUCK);
    }

    @Test
    @DisplayName("1분 사이 크게 뛰면 센서 이상 후보(SPIKE)")
    void spike() {
        List<Sample> h = new ArrayList<>(line(10, 600, 1));
        h.add(new Sample(NOW.plusSeconds(60), 1200));
        Forecast f = Forecast.none();
        assertThat(detector.detect(Metric.CO2, h, CO2_BASE, f, CO2, NOW.plusSeconds(60))).extracting(Finding::kind)
                .contains(Finding.Kind.SPIKE);
    }

    @Test
    @DisplayName("값이 5개보다 적으면 예측하지 않는다")
    void tooFewPoints() {
        assertThat(forecaster.forecast(line(2, 600, 50), CO2_BASE, NOW).etaMinutes()).isNull();
    }
}
