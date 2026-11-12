package kr.ac.chosun.mspace.climate.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 이상 탐지. 경계에 닿은 뒤가 아니라 닿기 전에(lead 안에) 알리는 것이 핵심이다 (재웅 11/12).
 * <ul>
 *   <li>OVER·UNDER: 이미 경계 밖</li>
 *   <li>RISING_SOON·FALLING_SOON: 지금 추세면 lead분(기본 15분) 안에 경계에 닿음</li>
 *   <li>SPIKE: 1분 사이 크게 뜀 (센서 이상 후보)</li>
 *   <li>STUCK: stuck분(기본 20분) 동안 값이 하나도 안 바뀜 (센서 멈춤 후보)</li>
 * </ul>
 * 끊김(OFFLINE)은 방 단위라 ClimateAgent가 본다.
 */
public final class AnomalyDetector {

    private final Duration lead;
    private final Duration stuck;

    public AnomalyDetector(Duration lead, Duration stuck) {
        this.lead = lead;
        this.stuck = stuck;
    }

    public List<Finding> detect(Metric metric, List<Sample> recent, Baseline b, Forecast f, MetricRule rule,
                                Instant now) {
        List<Finding> out = new ArrayList<>();
        if (recent.isEmpty()) {
            return out;
        }
        Sample last = recent.get(recent.size() - 1);
        double v = last.value();
        String name = label(metric);
        if (b.upper() != null && v >= b.upper()) {
            out.add(new Finding(Finding.Kind.OVER, metric,
                    "%s %s%s, 이 방 기준 %s%s를 넘었어요".formatted(name, metric.format(v), metric.unit(), metric.format(b.upper()), metric.unit()),
                    0));
        } else if (b.lower() != null && v <= b.lower()) {
            out.add(new Finding(Finding.Kind.UNDER, metric,
                    "%s %s%s, 이 방 기준 %s%s보다 낮아요".formatted(name, metric.format(v), metric.unit(), metric.format(b.lower()), metric.unit()),
                    0));
        } else if (f.etaMinutes() != null && f.etaMinutes() <= lead.toMinutes()) {
            boolean up = f.towards() == Forecast.Boundary.UPPER;
            out.add(new Finding(up ? Finding.Kind.RISING_SOON : Finding.Kind.FALLING_SOON, metric,
                    "%s가 1분에 %s%s씩 %s 중이에요. %d분 뒤 이 방 기준 %s%s에 닿을 것 같아요".formatted(name,
                            metric.formatRate(f.slopePerMinute()), metric.unit(), up ? "오르는" : "내리는", f.etaMinutes(),
                            metric.format(up ? b.upper() : b.lower()), metric.unit()),
                    f.etaMinutes()));
        }
        if (recent.size() >= 2) {
            Sample prev = recent.get(recent.size() - 2);
            boolean adjacent = Duration.between(prev.at(), last.at()).toMinutes() <= 2;
            if (adjacent && Math.abs(v - prev.value()) > rule.spikeJump()) {
                out.add(new Finding(Finding.Kind.SPIKE, metric,
                        "%s가 1분 사이 %s%s → %s%s로 튀었어요. 센서를 확인해 주세요".formatted(name, metric.format(prev.value()),
                                metric.unit(), metric.format(v), metric.unit()), null));
            }
        }
        Instant from = now.minus(stuck);
        List<Sample> window = recent.stream().filter(s -> !s.at().isBefore(from)).toList();
        if (window.size() >= stuck.toMinutes() / 2 && window.stream().allMatch(s -> s.value() == v)
                && !window.get(0).at().isAfter(from.plus(Duration.ofMinutes(5)))) {
            out.add(new Finding(Finding.Kind.STUCK, metric,
                    "%s가 %d분째 %s%s 그대로예요. 센서가 멈췄을 수 있어요".formatted(name, stuck.toMinutes(), metric.format(v),
                            metric.unit()), null));
        }
        return out;
    }

    public static String label(Metric m) {
        return switch (m) {
            case CO2 -> "CO2";
            case TEMPERATURE -> "온도";
            case HUMIDITY -> "습도";
        };
    }

}
