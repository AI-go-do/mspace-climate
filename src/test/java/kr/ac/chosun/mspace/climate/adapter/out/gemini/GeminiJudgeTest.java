package kr.ac.chosun.mspace.climate.adapter.out.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.chosun.mspace.climate.MutableClock;
import kr.ac.chosun.mspace.climate.appliance.ApplianceCommand;
import kr.ac.chosun.mspace.climate.appliance.ApplianceKind;
import kr.ac.chosun.mspace.climate.appliance.ClimateFacade;
import kr.ac.chosun.mspace.climate.application.ClimateStore;
import kr.ac.chosun.mspace.climate.application.Judge;
import kr.ac.chosun.mspace.climate.application.SensorReading;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 진짜 climate(MCP 서버 포함)를 띄우고 제미나이 자리에 가짜 서버를 둔다.
 * 가짜 제미나이는 첫 번째에 set_purifier를 부르고, 두 번째에 한 문장으로 답한다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GeminiJudgeTest {

    static final List<String> BODIES = new CopyOnWriteArrayList<>();
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final HttpServer GEMINI = fakeGemini();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("mspace.jwt.secret", () -> "test-secret-test-secret-test-secret-0123456789");
        r.add("management.server.port", () -> "0");
        r.add("climate.scheduler", () -> "false");
        r.add("climate.replay.enabled", () -> "false");
        r.add("climate.sim.enabled", () -> "false");
        r.add("climate.mqtt.enabled", () -> "false");
        r.add("climate.data-dir", () -> "");
        r.add("climate.judge.api-key", () -> "");
    }

    @AfterAll
    static void stop() {
        GEMINI.stop(0);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    ClimateStore store;

    @Autowired
    ClimateFacade facade;

    final JsonMapper json = JsonMapper.builder().build();

    GeminiJudge judge(Instant now) {
        JudgeProperties p = new JudgeProperties(true, "test-key", "gemini-test",
                "http://127.0.0.1:" + GEMINI.getAddress().getPort(), "http://127.0.0.1:" + port, 2, 3, 5,
                Duration.ofSeconds(10), 0, null, null, 0);
        return new GeminiJudge(p, json, new MutableClock(now));
    }

    @Test
    @DisplayName("제미나이가 MCP 도구로 공기청정기를 켜고 한 문장으로 답하면, 기기가 켜지고 한 일·이유가 돌아온다")
    void judgeUsesMcpTools() throws Exception {
        // given: 1번 방에 사람이 있고 CO2 값이 들어와 있다
        BODIES.clear();
        STATUS.set(200);
        Instant now = Instant.now();
        store.setOccupancy(1, true, now);
        store.accept(new SensorReading("g-1", 1, "CO2", null, 900, 24.0, 45.0, now));
        Recorder rec = new Recorder();

        // when
        try (GeminiJudge judge = judge(now)) {
            judge.ask(new Judge.Request(1, "1번 방", List.of("d-1"), List.of("CO2 900ppm")), rec);
            rec.result.get(20, TimeUnit.SECONDS);
        }

        // then
        assertThat(rec.fallback).isNull();
        assertThat(rec.did).containsExactly("공기청정기 켬");
        assertThat(rec.reason).contains("공기청정기");
        assertThat(rec.progress).contains("방 상태 보는 중 (room_status)", "공기청정기 켜는 중");
        assertThat(facade.states(1)).anyMatch(s -> s.kind() == ApplianceKind.PURIFIER
                && s.power() == ApplianceCommand.Power.ON);
        // 첫 요청: 도구 선언 4개 + room_status 결과가 프롬프트에, 키는 헤더로만
        assertThat(BODIES.get(0)).contains("\"set_purifier\"", "\"set_aircon\"", "\"set_ventilator\"", "\"room_status\"")
                .contains("coolTargetRangeC").doesNotContain("test-key");
        // 둘째 요청: 도구 결과를 functionResponse로 돌려줬다
        assertThat(BODIES.get(1)).contains("functionResponse").contains("\"ok\":true");
        facade.set(1, ApplianceKind.PURIFIER, false, "시험 끝");
    }

    @Test
    @DisplayName("제미나이가 429(한도)를 주면 규칙에게 넘긴다(fallback)")
    void quotaFallsBack() throws Exception {
        STATUS.set(429);
        Recorder rec = new Recorder();
        try (GeminiJudge judge = judge(Instant.now())) {
            judge.ask(new Judge.Request(1, "1번 방", List.of("d-2"), List.of("CO2 900ppm")), rec);
            rec.result.get(20, TimeUnit.SECONDS);
        } finally {
            STATUS.set(200);
        }
        assertThat(rec.fallback).isEqualTo("제미나이 한도");
    }

    @Test
    @DisplayName("시간당 한도를 넘으면 제미나이를 부르지 않고 바로 규칙에게 넘긴다")
    void hourlyLimit() {
        int n = 1;
        try (GeminiJudge judge = judge(Instant.now())) {
            assertThat(judge.take(n++)).isNull();
            assertThat(judge.take(n++)).isNull();
            assertThat(judge.take(n++)).isEqualTo("제미나이 시간당 2번 한도");
        }
    }

    @Test
    @DisplayName("하루 한도는 한 시간이 지나도 남는다")
    void dailyLimit() {
        int n = 1;
        MutableClock clock = new MutableClock(Instant.parse("2026-11-12T00:00:00Z"));
        JudgeProperties p = new JudgeProperties(true, "k", null, null, null, 2, 3, 0, null, 0, null, null, 0);
        try (GeminiJudge judge = new GeminiJudge(p, json, clock)) {
            judge.take(n++);
            judge.take(n++);
            clock.advance(Duration.ofMinutes(61));
            assertThat(judge.take(n++)).isNull();
            clock.advance(Duration.ofMinutes(61));
            assertThat(judge.take(n++)).isEqualTo("제미나이 하루 3번 한도");
            clock.advance(Duration.ofDays(1));
            assertThat(judge.take(n++)).isNull();
        }
    }

    @Test
    @DisplayName("제미나이 호출은 1분에 정해진 번수까지만, 1분이 지나면 다시 부른다")
    void callsPerMinute() {
        int n = 1;
        MutableClock clock = new MutableClock(Instant.parse("2026-11-12T00:00:00Z"));
        JudgeProperties p = new JudgeProperties(true, "k", null, null, null, 100, 100, 0, null, 3, null, null, 0);
        try (GeminiJudge judge = new GeminiJudge(p, json, clock)) {
            assertThat(judge.callSlot()).isNull();
            assertThat(judge.callSlot()).isNull();
            // 2번 쓴 뒤에는 새 판단(보통 2번 필요)을 시작하지 않는다
            assertThat(judge.take(n++)).isEqualTo("제미나이 분당 3번 한도");
            assertThat(judge.callSlot()).isNull();
            assertThat(judge.callSlot()).isEqualTo("제미나이 분당 3번 한도");
            clock.advance(Duration.ofSeconds(61));
            assertThat(judge.take(n++)).isNull();
            assertThat(judge.callSlot()).isNull();
        }
    }

    @Test
    @DisplayName("같은 방은 60분에 한 번만 묻고, 실제 방 밖(가상 방)은 시간당 1번이라 실제 방 몫이 남는다")
    void roomCooldownAndPriority() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-12T00:00:00Z"));
        JudgeProperties p = new JudgeProperties(true, "k", null, null, null, 2, 48, 0, null, 0,
                Duration.ofMinutes(60), List.of(1), 1);
        try (GeminiJudge judge = new GeminiJudge(p, json, clock)) {
            assertThat(judge.take(3)).isNull();
            clock.advance(Duration.ofMinutes(5));
            assertThat(judge.take(3)).isEqualTo("같은 방은 60분에 한 번만 제미나이");
            assertThat(judge.take(4)).isEqualTo("다른 방은 시간당 1번만 제미나이(실제 방 몫)");
            // 실제 방(1번)은 남겨 둔 몫으로 묻는다
            assertThat(judge.take(1)).isNull();
            clock.advance(Duration.ofMinutes(61));
            assertThat(judge.take(4)).isNull();
            assertThat(judge.take(3)).isEqualTo("다른 방은 시간당 1번만 제미나이(실제 방 몫)");
        }
    }

    @Test
    @DisplayName("키가 비면 판단 에이전트는 꺼져 있다(규칙만)")
    void noKeyDisabled() {
        assertThat(new JudgeProperties(true, " ", null, null, null, 0, 0, 0, null, 0, null, null, 0).ready()).isFalse();
        assertThat(new JudgeProperties(false, "k", null, null, null, 0, 0, 0, null, 0, null, null, 0).ready()).isFalse();
    }

    static class Recorder implements Judge.Listener {
        final List<String> progress = new CopyOnWriteArrayList<>();
        final CompletableFuture<Void> result = new CompletableFuture<>();
        volatile List<String> did;
        volatile String reason;
        volatile String fallback;

        @Override
        public void progress(Judge.Request r, String text) {
            progress.add(text);
        }

        @Override
        public void done(Judge.Request r, List<String> did, String reason) {
            this.did = did;
            this.reason = reason;
            result.complete(null);
        }

        @Override
        public void fallback(Judge.Request r, String why) {
            this.fallback = why;
            result.complete(null);
        }
    }

    static HttpServer fakeGemini() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                BODIES.add(body);
                String out;
                if (STATUS.get() != 200) {
                    out = "{\"error\":{\"code\":" + STATUS.get() + "}}";
                } else if (!body.contains("functionResponse")) {
                    out = """
                            {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"set_purifier",
                             "args":{"roomId":1,"on":true,"reason":"CO2가 높아 공기청정기를 켜요"}},"thoughtSignature":"sig"}]}}]}""";
                } else {
                    out = """
                            {"candidates":[{"content":{"role":"model","parts":[{"text":"CO2가 900ppm이라 공기청정기를 켰어요"}]}}]}""";
                }
                byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(STATUS.get(), bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
