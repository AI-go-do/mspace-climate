package kr.ac.chosun.mspace.climate.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MetricTest {

    @Test
    @DisplayName("화면 숫자: CO2·습도는 정수, 온도는 소수 한 자리")
    void format() {
        assertThat(Metric.CO2.format(715.3)).isEqualTo("715");
        assertThat(Metric.HUMIDITY.format(44.6)).isEqualTo("45");
        assertThat(Metric.TEMPERATURE.format(24.26)).isEqualTo("24.3");
        assertThat(Metric.TEMPERATURE.format(26.0)).isEqualTo("26");
    }

    @Test
    @DisplayName("1분당 변화는 작아도 0으로 보이지 않는다")
    void rate() {
        assertThat(Metric.TEMPERATURE.formatRate(0.06)).isEqualTo("0.06");
        assertThat(Metric.TEMPERATURE.formatRate(-0.1)).isEqualTo("0.1");
        assertThat(Metric.CO2.formatRate(12.4)).isEqualTo("12");
        assertThat(Metric.CO2.formatRate(2.5)).isEqualTo("2.5");
    }
}
