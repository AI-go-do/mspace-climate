package kr.ac.chosun.mspace.climate.adapter.out;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kr.ac.chosun.mspace.climate.application.Alert;
import kr.ac.chosun.mspace.climate.application.Notifier;
import kr.ac.chosun.mspace.climate.domain.Finding;
import kr.ac.chosun.mspace.climate.domain.Metric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * 방 사용자 알림을 core에 부탁한다 (POST {core}/internal/air-alerts, 서비스 토큰).
 * 누가 그 방을 쓰는지·알림함·메일·30분 거름은 core가 맡는다(core가 사람·알림 담당, climate는 공기만).
 * 관리자 알림(센서 이상)은 화면에만. 판단 흐름을 막지 않게 따로 스레드에서 보내고, 실패해도 화면 기록은 남는다.
 * 로그에는 방 번호·종류·결과만
 */
public class CoreAirAlertNotifier implements Notifier, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CoreAirAlertNotifier.class);

    private final URI endpoint;
    private final String token;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "climate-alert");
        t.setDaemon(true);
        return t;
    });

    public CoreAirAlertNotifier(String coreUrl, String token, HttpClient http) {
        this.endpoint = URI.create(coreUrl.replaceAll("/+$", "") + "/internal/air-alerts");
        this.token = token;
        this.http = http;
    }

    @Override
    public boolean reachesStudents() {
        return true;
    }

    @Override
    public void send(Alert a) {
        String kind = kind(a);
        if (a.audience() != Alert.Audience.ROOM || kind == null) {
            log.info("알림 방={} 대상={} 지표={} 종류={}", a.roomId(), a.audience(), a.metric(), a.kind());
            return;
        }
        worker.submit(() -> post(a, kind));
    }

    private void post(Alert a, String kind) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", "climate-%d-%s-%d".formatted(a.roomId(), kind, a.at().getEpochSecond() / 60));
        body.put("roomId", a.roomId());
        body.put("kind", kind);
        body.put("title", title(kind));
        body.put("body", a.message());
        try {
            HttpRequest req = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("방 알림 core 방={} 종류={} 상태={} 결과={}", a.roomId(), kind, res.statusCode(), result(res));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("방 알림 core 실패 방={} 종류={} ({})", a.roomId(), kind, e.getClass().getSimpleName());
        }
    }

    private String result(HttpResponse<String> res) {
        try {
            return String.valueOf(json.readTree(res.body()).path("result").asString(""));
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** core에 보낼 종류. 학생에게 알릴 만한 것만(센서 이상은 null) */
    static String kind(Alert a) {
        if (a.metric() == null || a.kind() == null) {
            return null;
        }
        boolean high = a.kind() == Finding.Kind.OVER || a.kind() == Finding.Kind.RISING_SOON;
        boolean low = a.kind() == Finding.Kind.UNDER || a.kind() == Finding.Kind.FALLING_SOON;
        if (a.metric() == Metric.CO2 && high) {
            return a.kind() == Finding.Kind.OVER ? "CO2_HIGH" : "CO2_SOON";
        }
        if (a.metric() == Metric.TEMPERATURE && (high || low)) {
            return high ? "TEMP_HIGH" : "TEMP_LOW";
        }
        if (a.metric() == Metric.HUMIDITY && high) {
            return "HUMIDITY_HIGH";
        }
        return null;
    }

    static String title(String kind) {
        return switch (kind) {
            case "CO2_SOON" -> "곧 환기가 필요해요";
            case "CO2_HIGH" -> "환기해 주세요";
            case "TEMP_HIGH" -> "방이 더워지고 있어요";
            case "TEMP_LOW" -> "방이 추워지고 있어요";
            default -> "방이 습해지고 있어요";
        };
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
