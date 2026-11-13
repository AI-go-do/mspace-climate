package kr.ac.chosun.mspace.climate.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.climate.application.RoomSimulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CoreSensorFeedTest {

    record Got(String path, String auth, JsonNode body) {
    }

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant T0 = Instant.parse("2026-11-13T00:17:00Z");   // 09:17 KST

    HttpServer core;
    final List<Got> got = new ArrayList<>();
    int status = 200;
    CoreSensorFeed feed;

    @BeforeEach
    void setUp() throws Exception {
        core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        core.createContext("/", ex -> {
            synchronized (got) {
                got.add(new Got(ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("Authorization"),
                        JSON.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))));
            }
            byte[] out = "{\"result\":\"STORED\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        core.start();
        feed = new CoreSensorFeed("http://127.0.0.1:" + core.getAddress().getPort() + "/",
                CoreSensorFeed.parse("sensor-1:1:t1,keypad-2:2:k2,sensor-2:2:t2"), Duration.ofSeconds(20),
                HttpClient.newHttpClient(), Runnable::run);
    }

    @AfterEach
    void tearDown() {
        core.stop(0);
    }

    static RoomSimulator.State state(int room, double co2, boolean occupied) {
        return new RoomSimulator.State(room, co2, 22.0, 47.0, occupied, null, null, null);
    }

    List<Got> of(String kind) {
        synchronized (got) {
            return got.stream().filter(g -> kind.equals(g.body().path("kind").asString(""))).toList();
        }
    }

    @Test
    @DisplayName("MSPACE_DEVICES에서 방마다 sensor 장치 토큰만 고른다")
    void 센서_장치만_고름() {
        var d = CoreSensorFeed.parse("keypad-1:1:a,sensor-1:1:b,sensor-3:x:c,qr-2:2:d,sensor-5:5:e,bad");

        assertThat(d).containsOnlyKeys(1, 5);
        assertThat(d.get(1).deviceId()).isEqualTo("sensor-1");
        assertThat(d.get(1).toString()).doesNotContain("b");
    }

    @Test
    @DisplayName("CO2·온도·습도는 1분 평균을 분이 바뀔 때 한 번, source=SIM, 그 방 장치 토큰으로 보낸다")
    void CO2는_1분_평균_한_번() {
        // given: 09:17 동안 1초마다 600, 700
        for (int s = 0; s < 60; s++) {
            feed.simulated(state(1, s % 2 == 0 ? 600 : 700, false), T0.plusSeconds(s), true, false);
        }
        assertThat(of("CO2")).isEmpty();   // 분이 끝나기 전에는 안 보냄

        // when: 09:18 첫 값
        feed.simulated(state(1, 800, false), T0.plusSeconds(60), true, false);

        // then
        var co2 = of("CO2");
        assertThat(co2).hasSize(1);
        Got g = co2.getFirst();
        assertThat(g.path()).isEqualTo("/api/sensor-events");
        assertThat(g.auth()).isEqualTo("Bearer t1");
        assertThat(g.body().path("eventId").asString()).isEqualTo("sim-co2-1-202611130917");
        assertThat(g.body().path("deviceId").asString()).isEqualTo("sensor-1");
        assertThat(g.body().path("ppm").asInt()).isEqualTo(650);
        assertThat(g.body().path("source").asString()).isEqualTo("SIM");
        assertThat(g.body().path("temperatureC").asDouble()).isEqualTo(22.0);
        assertThat(g.body().path("humidityPct").asDouble()).isEqualTo(47.0);
        assertThat(g.body().path("observedAt").asString()).isEqualTo("2026-11-13T09:17:00+09:00");
        assertThat(of("OCCUPANCY")).isEmpty();   // 1번 방(실제 방) 재실은 실제 센서 몫
    }

    @Test
    @DisplayName("실제 값이 오는 방(climate가 시뮬레이션 값을 안 씀)은 CO2를 보내지 않는다")
    void 실제_값_방은_안_보냄() {
        for (int s = 0; s <= 60; s++) {
            feed.simulated(state(1, 600, false), T0.plusSeconds(s), false, false);
        }

        assertThat(got).isEmpty();
    }

    @Test
    @DisplayName("가상 방 재실은 바뀔 때 바로, 그대로면 20초마다 살아 있다는 신호를 보낸다")
    void 가상_방_재실() {
        // given: 처음 빈 방
        feed.simulated(state(2, 560, false), T0, true, true);
        feed.simulated(state(2, 560, false), T0.plusSeconds(5), true, true);
        assertThat(of("OCCUPANCY")).hasSize(1);

        // when: 10초에 입실, 30초에 그대로(20초 지남)
        feed.simulated(state(2, 560, true), T0.plusSeconds(10), true, true);
        feed.simulated(state(2, 560, true), T0.plusSeconds(20), true, true);
        feed.simulated(state(2, 560, true), T0.plusSeconds(30), true, true);

        // then
        var occ = of("OCCUPANCY");
        assertThat(occ).extracting(g -> g.body().path("occupied").asBoolean()).containsExactly(false, true, true);
        assertThat(occ.get(1).body().path("eventId").asString()).isEqualTo("sim-occ-2-" + T0.plusSeconds(10).getEpochSecond());
        assertThat(occ.get(1).auth()).isEqualTo("Bearer t2");
        assertThat(occ.get(1).body().path("sources").path("sim").asBoolean()).isTrue();
        assertThat(occ.get(1).body().has("ppm")).isFalse();
    }

    @Test
    @DisplayName("장치 토큰이 없는 방은 아무것도 보내지 않는다")
    void 토큰_없는_방() {
        for (int s = 0; s <= 60; s++) {
            feed.simulated(state(3, 600, true), T0.plusSeconds(s), true, true);
        }

        assertThat(got).isEmpty();
    }

    @Test
    @DisplayName("core가 꺼져 있거나 거절해도 예외 없이 지나간다")
    void core_실패해도_계속() {
        status = 401;
        assertThatCode(() -> {
            for (int s = 0; s <= 60; s++) {
                feed.simulated(state(2, 600, true), T0.plusSeconds(s), true, true);
            }
        }).doesNotThrowAnyException();

        core.stop(0);
        var down = new CoreSensorFeed("http://127.0.0.1:1", Map.of(2, new CoreSensorFeed.Device("sensor-2", "t")),
                Duration.ofSeconds(20), HttpClient.newHttpClient(), Runnable::run);
        assertThatCode(() -> {
            for (int s = 0; s <= 60; s++) {
                down.simulated(state(2, 600, true), T0.plusSeconds(s), true, true);
            }
        }).doesNotThrowAnyException();
    }
}
