package kr.ac.chosun.mspace.climate.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BaselineCalculatorTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z"); // 15:00 KST
    static final MetricRule TEMP = new MetricRule(18.0, 28.0, 1.5, 4, false);
    static final MetricRule CO2 = new MetricRule(null, 1000.0, 150, 400, false);

    final BaselineCalculator calc = new BaselineCalculator(SEOUL, 2.5, 30);

    /** 지난 days일 동안 매일 hour시(KST) 앞뒤 1시간에 value ± jitter 값 */
    static List<Sample> daily(int days, int hourKst, double value, double jitter) {
        List<Sample> out = new ArrayList<>();
        for (int d = days; d >= 1; d--) {
            Instant base = NOW.minus(Duration.ofDays(d)).atZone(SEOUL).withHour(hourKst).withMinute(0).toInstant();
            for (int m = -60; m < 60; m++) {
                out.add(new Sample(base.plus(Duration.ofMinutes(m)), value + (m % 2 == 0 ? jitter : -jitter)));
            }
        }
        return out;
    }

    @Test
    @DisplayName("같은 시간대 값이 충분하면 그 시간대 평균·흩어짐으로 경계를 잡는다")
    void hourlyBaseline() {
        // given: 15시엔 25도, 새벽 3시엔 19도인 방
        List<Sample> h = new ArrayList<>(daily(3, 15, 25.0, 0.4));
        h.addAll(daily(3, 3, 19.0, 0.4));
        h.sort((a, b) -> a.at().compareTo(b.at()));

        // when
        Baseline b = calc.baseline(h, NOW, TEMP);

        // then: 오후 3시 기준선은 25도 근처 (새벽 값이 섞이지 않음)
        assertThat(b.scope()).isEqualTo("HOUR");
        assertThat(b.mean()).isEqualTo(25.0);
        assertThat(b.learning()).isFalse();
        assertThat(b.upper()).isEqualTo(26.5);   // 흩어짐 0.4×2.5=1.0 < 최소 거리 1.5
        assertThat(b.lower()).isEqualTo(23.5);
    }

    @Test
    @DisplayName("방마다 평소 값이 다르면 경계도 방마다 다르다")
    void perRoom() {
        Baseline warm = calc.baseline(daily(2, 15, 26.0, 0.3), NOW, TEMP);
        Baseline cool = calc.baseline(daily(2, 15, 22.0, 0.3), NOW, TEMP);
        assertThat(warm.upper()).isGreaterThan(cool.upper());
    }

    @Test
    @DisplayName("평균 + k·흩어짐이 안전 한계를 넘으면 안전 한계로 자른다 (CO2 1000ppm 팀 규칙)")
    void cappedByHardLimit() {
        Baseline b = calc.baseline(daily(2, 15, 900, 80), NOW, CO2);
        assertThat(b.upper()).isEqualTo(1000.0);
        assertThat(b.lower()).isNull();
    }

    @Test
    @DisplayName("값이 모자라면 학습 중으로 보고 안전 한계만 쓴다")
    void learning() {
        List<Sample> few = List.of(new Sample(NOW.minusSeconds(60), 24), new Sample(NOW, 24.2));
        Baseline b = calc.baseline(few, NOW, TEMP);
        assertThat(b.learning()).isTrue();
        assertThat(b.upper()).isEqualTo(28.0);
        assertThat(b.lower()).isEqualTo(18.0);
    }

    @Test
    @DisplayName("fixedUpper면 위 경계는 방 기준선과 상관없이 안전 한계(CO2 1000)로 고정된다")
    void fixedUpperUsesHardMax() {
        // given: 늘 600ppm 안팎인 방(기준선이면 위 경계가 1000보다 한참 낮음)
        var history = new ArrayList<Sample>();
        Instant t0 = Instant.parse("2026-11-10T06:00:00Z");
        for (int i = 0; i < 60; i++) {
            history.add(new Sample(t0.plusSeconds(60L * i), 600 + (i % 5) * 10));
        }
        var calc = new BaselineCalculator(ZoneId.of("Asia/Seoul"), 2.5, 30);
        // when
        Baseline usual = calc.baseline(history, t0.plusSeconds(3600), CO2);
        Baseline fixed = calc.baseline(history, t0.plusSeconds(3600), new MetricRule(null, 1000.0, 150, 400, true));
        // then
        assertThat(usual.upper()).isLessThan(1000.0);
        assertThat(fixed.upper()).isEqualTo(1000.0);
        assertThat(fixed.mean()).isEqualTo(usual.mean());
    }
}
