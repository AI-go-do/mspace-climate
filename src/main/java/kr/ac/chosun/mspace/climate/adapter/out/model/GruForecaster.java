package kr.ac.chosun.mspace.climate.adapter.out.model;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.climate.domain.Baseline;
import kr.ac.chosun.mspace.climate.domain.Forecast;
import kr.ac.chosun.mspace.climate.domain.Forecaster;
import kr.ac.chosun.mspace.climate.domain.Metric;
import kr.ac.chosun.mspace.climate.domain.Sample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실습 시계열 모델(GRU, 재웅 실습 저장소 AIGJ_01_012_timeseries-inference 과제 3)로 CO2·온도를 예측한다 (재웅 11/13 "가져와서 써 보자").
 * 모델 파일(src/main/resources/model)은 실습 맥 data/models 그대로(forecast.onnx sha256 f36ae787…). 보드 A가 아니라 이 서버 CPU에서 돈다.
 *
 * <p>입력은 실습 서비스(service/engine.py)와 같게 만든다 (실습 contracts/model-io.md):
 * <ul>
 *   <li>5분 칸 [t−5분, t)의 평균을 t에 붙이고, 끝난 칸 24개(2시간)</li>
 *   <li>칸마다 co2·온도·습도 z(실습실 평균·표준편차로) + 쓸 수 있나(1/0) + 하루 중 시각·요일 sin/cos 4개 = 10개</li>
 *   <li>15분 이하 빈칸은 앞뒤 값으로 선형 보간(쓸 수 있음, 관측은 아님)</li>
 *   <li>공간은 실습실(학습 때 가장 비슷한 방)</li>
 * </ul>
 * 출력은 12칸(5~60분 뒤) × (co2, 온도)의 변화량(정규화 단위). 습도·값 부족(2시간 창의 75% 미만, 지금 칸 관측 없음)·오류면 추세(fallback)로.
 */
public class GruForecaster implements Forecaster {

    private static final Logger log = LoggerFactory.getLogger(GruForecaster.class);
    static final int STEP_MINUTES = 5;
    static final int MAX_INTERP_SLOTS = 3;          // 15분 이하만 보간 (실습 preprocess max_interp_minutes)
    static final double MIN_WINDOW = 0.75;           // 과거 창에서 값이 있어야 하는 비율
    static final int SHOW_MINUTES = 30;              // 화면 예측선 길이(추세와 같게)
    static final Metric[] INPUTS = {Metric.CO2, Metric.TEMPERATURE, Metric.HUMIDITY};

    private final OrtEnvironment env;
    private final OrtSession session;
    private final int lookback;
    private final int horizon;
    private final int spaces;
    private final double[] mean = new double[3];
    private final double[] std = new double[3];
    private final Forecaster fallback;
    private final ZoneId zone;

    GruForecaster(byte[] onnx, JsonNode meta, JsonNode normalization, Forecaster fallback, ZoneId zone) throws OrtException {
        String variant = meta.path("variant").asString();
        if (!"no_schedule".equals(variant) || !"delta".equals(meta.path("target").asString())
                || meta.path("n_past").asInt() != 10 || meta.path("n_future").asInt() != 4) {
            throw new IllegalArgumentException("이 모델 모양은 아직 못 써요: " + variant);
        }
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(1);
        this.session = env.createSession(onnx, o);
        this.lookback = meta.path("lookback_steps").asInt();
        this.horizon = 12;
        this.spaces = meta.path("n_spaces").asInt();
        String space = meta.path("spaces").get(0).asString();
        JsonNode stats = normalization.path("values").path(space);
        String[] names = {"co2", "temperature", "humidity"};
        for (int j = 0; j < 3; j++) {
            mean[j] = stats.path(names[j]).path("mean").asDouble();
            std[j] = stats.path(names[j]).path("std").asDouble();
        }
        this.fallback = fallback;
        this.zone = zone;
    }

    /** 클래스패스 model/에서 읽는다. 없거나 못 읽으면 null (추세만 쓴다) */
    public static GruForecaster load(ClassLoader cl, Forecaster fallback, ZoneId zone) {
        try (InputStream onnx = cl.getResourceAsStream("model/forecast.onnx");
             InputStream meta = cl.getResourceAsStream("model/forecast.json");
             InputStream norm = cl.getResourceAsStream("model/normalization.json")) {
            if (onnx == null || meta == null || norm == null) {
                log.info("예측 모델 파일이 없어 추세 예측만 써요");
                return null;
            }
            JsonMapper json = JsonMapper.builder().build();
            GruForecaster f = new GruForecaster(onnx.readAllBytes(), json.readTree(meta), json.readTree(norm), fallback, zone);
            log.info("예측 모델(GRU) 켬: 과거 {}칸, 5~60분", f.lookback);
            return f;
        } catch (IOException | OrtException | RuntimeException e) {
            log.warn("예측 모델을 못 읽어 추세 예측만 써요: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public Forecast forecast(List<Sample> recent, Baseline baseline, Instant now) {
        return fallback.forecast(recent, baseline, now);
    }

    @Override
    public Forecast forecast(Metric metric, Map<Metric, List<Sample>> history, Baseline baseline, Instant now) {
        int j = metric == Metric.CO2 ? 0 : metric == Metric.TEMPERATURE ? 1 : -1;
        List<Sample> own = history.getOrDefault(metric, List.of());
        if (j < 0 || own.isEmpty()) {
            return fallback.forecast(metric, history, baseline, now);
        }
        try {
            double[] change = predict(history, now, j);
            if (change == null) {
                return fallback.forecast(metric, history, baseline, now);
            }
            return toForecast(change, own.get(own.size() - 1).value(), lastLabel(now), baseline, now);
        } catch (OrtException | RuntimeException e) {
            log.warn("예측 모델 계산 실패, 추세로: {}", e.getClass().getSimpleName());
            return fallback.forecast(metric, history, baseline, now);
        }
    }

    /** 끝난 마지막 5분 칸의 끝 시각 */
    Instant lastLabel(Instant now) {
        Instant m = now.truncatedTo(ChronoUnit.MINUTES);
        long min = m.getEpochSecond() / 60;
        return Instant.ofEpochSecond((min - Math.floorMod(min, STEP_MINUTES)) * 60);
    }

    /**
     * 12칸의 변화량(원래 단위, 마지막 칸 평균 대비)을 낸다. 값이 모자라면 null
     */
    double[] predict(Map<Metric, List<Sample>> history, Instant now, int target) throws OrtException {
        Instant last = lastLabel(now);
        Instant first = last.minus(Duration.ofMinutes((long) STEP_MINUTES * (lookback - 1)));
        // 보간 여유로 앞에 3칸 더
        Instant from = first.minus(Duration.ofMinutes((long) STEP_MINUTES * (MAX_INTERP_SLOTS + 1)));
        int n = lookback + MAX_INTERP_SLOTS + 1;
        double[][] slot = new double[3][n];
        boolean[][] observed = new boolean[3][n];
        for (int k = 0; k < 3; k++) {
            double[] sum = new double[n];
            int[] cnt = new int[n];
            List<Sample> h = history.getOrDefault(INPUTS[k], List.of());
            for (int i = h.size() - 1; i >= 0; i--) {
                Sample s = h.get(i);
                if (s.at().isBefore(from.minus(Duration.ofMinutes(STEP_MINUTES)))) {
                    break;
                }
                // 칸 [t−5, t) → t. 1분 값의 시각은 그 분의 시작
                long minutes = Duration.between(from.minus(Duration.ofMinutes(STEP_MINUTES)), s.at()).toMinutes();
                int idx = (int) Math.floorDiv(minutes, STEP_MINUTES);
                if (idx >= 0 && idx < n && !s.at().isBefore(from.minus(Duration.ofMinutes(STEP_MINUTES)))) {
                    sum[idx] += s.value();
                    cnt[idx]++;
                }
            }
            for (int i = 0; i < n; i++) {
                observed[k][i] = cnt[i] > 0;
                slot[k][i] = cnt[i] > 0 ? sum[i] / cnt[i] : Double.NaN;
            }
        }
        int off = MAX_INTERP_SLOTS + 1;     // slot 배열에서 과거 창 첫 칸 위치
        if (!observed[target][n - 1]) {
            return null;                    // 지금 칸이 관측이 아니면 예측 안 함(실습 서비스와 같음)
        }
        boolean[][] usable = new boolean[3][n];
        for (int k = 0; k < 3; k++) {
            interpolate(slot[k], observed[k], usable[k]);
        }
        int ok = 0;
        for (int i = off; i < n; i++) {
            ok += usable[target][i] ? 1 : 0;
        }
        if (ok < Math.ceil(lookback * MIN_WINDOW)) {
            return null;
        }
        float[][][] past = new float[1][lookback][10];
        for (int i = 0; i < lookback; i++) {
            int p = off + i;
            for (int k = 0; k < 3; k++) {
                past[0][i][k] = usable[k][p] ? (float) ((slot[k][p] - mean[k]) / std[k]) : 0f;
                past[0][i][3 + k] = usable[k][p] ? 1f : 0f;
            }
            float[] cal = calendar(first.plus(Duration.ofMinutes((long) STEP_MINUTES * i)));
            System.arraycopy(cal, 0, past[0][i], 6, 4);
        }
        float[][][] future = new float[1][horizon][];
        for (int k = 0; k < horizon; k++) {
            future[0][k] = calendar(last.plus(Duration.ofMinutes((long) STEP_MINUTES * (k + 1))));
        }
        float[][] space = new float[1][spaces];
        space[0][0] = 1f;                    // 실습실
        try (OnnxTensor a = OnnxTensor.createTensor(env, past);
             OnnxTensor b = OnnxTensor.createTensor(env, future);
             OnnxTensor c = OnnxTensor.createTensor(env, space);
             OrtSession.Result r = session.run(Map.of("past", a, "future", b, "space", c))) {
            float[][][] delta = (float[][][]) r.get(0).getValue();
            double[] out = new double[horizon];
            for (int k = 0; k < horizon; k++) {
                out[k] = delta[0][k][target] * std[target];
            }
            return out;
        }
    }

    /** 15분 이하 빈칸만 앞뒤 관측값 사이 직선으로 채운다 */
    static void interpolate(double[] v, boolean[] observed, boolean[] usable) {
        int prev = -1;
        for (int i = 0; i < v.length; i++) {
            if (!observed[i]) {
                continue;
            }
            usable[i] = true;
            int gap = i - prev - 1;
            if (prev >= 0 && gap > 0 && gap <= MAX_INTERP_SLOTS) {
                for (int g = 1; g <= gap; g++) {
                    v[prev + g] = v[prev] + (v[i] - v[prev]) * g / (gap + 1);
                    usable[prev + g] = true;
                }
            }
            prev = i;
        }
    }

    /** 칸 끝 t → 칸 시작(t−5분, 서울)의 하루 중 시각 sin/cos, 요일(월 0) sin/cos */
    float[] calendar(Instant label) {
        ZonedDateTime t = label.minus(Duration.ofMinutes(STEP_MINUTES)).atZone(zone);
        double tod = (t.getHour() * 60 + t.getMinute()) / 1440.0;
        double dow = (t.getDayOfWeek().getValue() - 1) / 7.0;
        return new float[] {(float) Math.sin(2 * Math.PI * tod), (float) Math.cos(2 * Math.PI * tod),
                (float) Math.sin(2 * Math.PI * dow), (float) Math.cos(2 * Math.PI * dow)};
    }

    /**
     * 변화량 12칸 → 화면·판단용 예측. 변화량은 지금 값에 더한다(모델은 "지금에서 얼마나 바뀌나"를 낸다).
     * 칸 k의 시각 = 마지막 칸 끝 + 5(k+1)분. 경계에 닿는 분은 지금(0분) → 칸들 사이를 직선으로 보고 처음 닿는 곳
     */
    Forecast toForecast(double[] change, double current, Instant last, Baseline b, Instant now) {
        double[] at = new double[horizon];
        double[] value = new double[horizon];
        for (int k = 0; k < horizon; k++) {
            at[k] = Duration.between(now, last.plus(Duration.ofMinutes((long) STEP_MINUTES * (k + 1)))).toSeconds() / 60.0;
            value[k] = current + change[k];
        }
        Integer eta = null;
        Forecast.Boundary towards = null;
        if (b.upper() != null && current < b.upper()) {
            eta = cross(at, value, current, b.upper(), true);
            towards = eta == null ? null : Forecast.Boundary.UPPER;
        }
        if (eta == null && b.lower() != null && current > b.lower()) {
            eta = cross(at, value, current, b.lower(), false);
            towards = eta == null ? null : Forecast.Boundary.LOWER;
        }
        // 1분당 변화: 15분 뒤 칸까지의 기울기
        int k15 = Math.min(horizon - 1, 2);
        double slope = at[k15] <= 0 ? 0 : (value[k15] - current) / at[k15];
        List<Sample> points = new ArrayList<>();
        for (int k = 0; k < horizon && at[k] <= SHOW_MINUTES; k++) {
            points.add(new Sample(last.plus(Duration.ofMinutes((long) STEP_MINUTES * (k + 1))), Math.round(value[k] * 100) / 100.0));
        }
        return new Forecast(Math.round(slope * 100) / 100.0, eta, towards, points, Forecast.GRU);
    }

    private static Integer cross(double[] at, double[] value, double current, double limit, boolean up) {
        double pt = 0;
        double pv = current;
        for (int k = 0; k < at.length; k++) {
            boolean hit = up ? value[k] >= limit : value[k] <= limit;
            if (hit) {
                double frac = (limit - pv) / (value[k] - pv);
                return (int) Math.ceil(pt + frac * (at[k] - pt) - 1e-6);
            }
            pt = at[k];
            pv = value[k];
        }
        return null;
    }
}
