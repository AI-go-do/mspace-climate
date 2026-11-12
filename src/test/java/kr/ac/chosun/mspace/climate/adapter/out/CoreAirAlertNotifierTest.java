package kr.ac.chosun.mspace.climate.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import kr.ac.chosun.mspace.climate.application.Alert;
import kr.ac.chosun.mspace.climate.domain.Finding;
import kr.ac.chosun.mspace.climate.domain.Metric;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoreAirAlertNotifierTest {

    record Got(String path, String auth, String body) {
    }

    HttpServer core;
    final LinkedBlockingQueue<Got> got = new LinkedBlockingQueue<>();
    CoreAirAlertNotifier notifier;

    @BeforeEach
    void setUp() throws Exception {
        core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        core.createContext("/", ex -> {
            got.add(new Got(ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("Authorization"),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] out = "{\"result\":\"SENT\",\"recipients\":2}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        core.start();
        notifier = new CoreAirAlertNotifier("http://127.0.0.1:" + core.getAddress().getPort() + "/", "svc-token",
                HttpClient.newHttpClient());
    }

    @AfterEach
    void tearDown() {
        notifier.close();
        core.stop(0);
    }

    Alert alert(Alert.Audience to, Metric m, Finding.Kind k) {
        return new Alert(Instant.parse("2026-11-12T09:30:00Z"), 3, to, m, k, "3번 방 이산화탄소가 12분 뒤 1000ppm을 넘을 것 같아요", 12);
    }

    @Test
    @DisplayName("방 사용자 알림(CO2 곧 넘음)은 core /internal/air-alerts에 서비스 토큰으로 방 번호·종류·문구를 부탁한다")
    void roomAlertGoesToCore() throws Exception {
        notifier.send(alert(Alert.Audience.ROOM, Metric.CO2, Finding.Kind.RISING_SOON));

        Got g = got.poll(5, TimeUnit.SECONDS);
        assertThat(g).isNotNull();
        assertThat(g.path()).isEqualTo("/internal/air-alerts");
        assertThat(g.auth()).isEqualTo("Bearer svc-token");
        assertThat(g.body()).contains("\"roomId\":3", "\"kind\":\"CO2_SOON\"", "\"title\":\"곧 환기가 필요해요\"",
                "12분 뒤 1000ppm", "\"eventId\":\"climate-3-CO2_SOON-");
        assertThat(notifier.reachesStudents()).isTrue();
    }

    @Test
    @DisplayName("관리자 알림(센서 이상)은 core로 보내지 않는다")
    void adminAlertStaysHere() throws Exception {
        notifier.send(alert(Alert.Audience.ADMIN, null, Finding.Kind.OFFLINE));
        notifier.send(alert(Alert.Audience.ROOM, Metric.CO2, Finding.Kind.SPIKE));
        assertThat(got.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("종류: CO2 넘음·곧 넘음, 온도 높음·낮음, 습도 높음만 학생에게 간다")
    void kinds() {
        assertThat(CoreAirAlertNotifier.kind(alert(Alert.Audience.ROOM, Metric.CO2, Finding.Kind.OVER))).isEqualTo("CO2_HIGH");
        assertThat(CoreAirAlertNotifier.kind(alert(Alert.Audience.ROOM, Metric.TEMPERATURE, Finding.Kind.RISING_SOON))).isEqualTo("TEMP_HIGH");
        assertThat(CoreAirAlertNotifier.kind(alert(Alert.Audience.ROOM, Metric.TEMPERATURE, Finding.Kind.UNDER))).isEqualTo("TEMP_LOW");
        assertThat(CoreAirAlertNotifier.kind(alert(Alert.Audience.ROOM, Metric.HUMIDITY, Finding.Kind.OVER))).isEqualTo("HUMIDITY_HIGH");
        assertThat(CoreAirAlertNotifier.kind(alert(Alert.Audience.ROOM, Metric.HUMIDITY, Finding.Kind.UNDER))).isNull();
        assertThat(CoreAirAlertNotifier.kind(alert(Alert.Audience.ROOM, Metric.CO2, Finding.Kind.STUCK))).isNull();
    }
}
