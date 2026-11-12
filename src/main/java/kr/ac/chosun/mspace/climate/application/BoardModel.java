package kr.ac.chosun.mspace.climate.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 보드 A 시계열 모델(재웅 실습 추론 서비스)이 MQTT로 내는 공간별 최신 결과.
 * 60분 예측(5분 간격 12개), 오토인코더 이상 점수, 재실 확률, 환기 확률. 15분보다 오래된 결과는 쓰지 않는다.
 */
public class BoardModel {

    public record Point(int minutes, double value) {
    }

    public record State(String slug, String space, Instant slot, Instant receivedAt, Double co2Now,
                        List<Point> co2, Double tempNow, List<Point> temp, Double anomalyScore,
                        Double anomalyThreshold, boolean anomaly, String anomalyCause, Double occupiedProbability,
                        Double ventilationProbability) {
    }

    static final Duration FRESH = Duration.ofMinutes(15);

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public void put(State s) {
        states.put(s.slug(), s);
    }

    public Optional<State> fresh(String slug, Instant now) {
        State s = states.get(slug);
        if (s == null || s.receivedAt().plus(FRESH).isBefore(now)) {
            return Optional.empty();
        }
        return Optional.of(s);
    }

    public List<State> all() {
        return List.copyOf(states.values());
    }
}
