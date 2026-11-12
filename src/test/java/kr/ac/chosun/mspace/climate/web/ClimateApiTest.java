package kr.ac.chosun.mspace.climate.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 진짜 climate를 띄우고 core 자리에 가짜 서버를 둔다. 가짜 core는 회원 1이면 관리자(200), 아니면 403.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClimateApiTest {

    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";
    static final HttpServer CORE = fakeCore();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("mspace.jwt.secret", () -> SECRET);
        r.add("management.server.port", () -> "0");
        r.add("climate.scheduler", () -> "false");
        r.add("climate.data-dir", () -> "");
        r.add("climate.core-uri", () -> "http://127.0.0.1:" + CORE.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        CORE.stop(0);
    }

    @Value("${local.server.port}")
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("실습실(1번 방) 실제 값 하나가 들어오면 1번은 REAL, 2~5번은 비슷한 값의 DERIVED로 보인다")
    void ingestAndDerive() throws Exception {
        String now = OffsetDateTime.now(ZoneOffset.ofHours(9)).toString();
        HttpResponse<String> in = post("/internal/sensor-events", null, """
                {"eventId":"t-1","roomId":1,"deviceId":"sensor-1","kind":"CO2","ppm":700,
                 "temperatureC":24.0,"humidityPct":45.0,"observedAt":"%s"}""".formatted(now));
        assertThat(in.body()).contains("STORED");

        HttpResponse<String> rooms = get("/api/climate/rooms", token(2));
        assertThat(rooms.statusCode()).isEqualTo(200);
        assertThat(rooms.body()).contains("\"roomId\":1,\"roomName\":\"1번 방\",\"source\":\"REAL\"");
        assertThat(rooms.body()).contains("\"roomId\":5,\"roomName\":\"5번 방\",\"source\":\"DERIVED\"");
        assertThat(rooms.body()).contains("\"appliances\":[{\"applianceId\":\"ac-1\"");
    }

    @Test
    @DisplayName("같은 eventId를 두 번 받으면 한 번만 센다")
    void duplicate() throws Exception {
        String body = """
                {"eventId":"dup-1","roomId":1,"kind":"CO2","ppm":700,"observedAt":"%s"}"""
                .formatted(OffsetDateTime.now(ZoneOffset.ofHours(9)));
        assertThat(post("/internal/sensor-events", null, body).body()).contains("STORED");
        assertThat(post("/internal/sensor-events", null, body).body()).contains("IGNORED");
    }

    @Test
    @DisplayName("로그인 없이 방 공기를 보면 401 {code, message}")
    void noToken() throws Exception {
        HttpResponse<String> res = get("/api/climate/rooms", null);
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    @DisplayName("관리자가 아니면 기기를 직접 못 바꾼다 (관리자인지는 core가 정함)")
    void notAdmin() throws Exception {
        HttpResponse<String> res = post("/api/climate/rooms/1/appliances/ac-1/commands", token(2),
                "{\"power\":\"ON\",\"mode\":\"COOL\",\"targetC\":25}");
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.body()).contains("ADMIN_ONLY");
    }

    @Test
    @DisplayName("관리자는 기기를 바꾸고, 조치 기록에 ADMIN으로 남는다")
    void adminCommand() throws Exception {
        HttpResponse<String> res = post("/api/climate/rooms/1/appliances/ac-1/commands", token(1),
                "{\"power\":\"ON\",\"mode\":\"COOL\",\"targetC\":25}");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"power\":\"ON\"").contains("\"by\":\"ADMIN\"");
        assertThat(get("/api/climate/log", token(1)).body()).contains("관리자가 직접 바꿈");
    }

    @Test
    @DisplayName("다른 방 기기 id로는 못 바꾼다 (404)")
    void wrongRoom() throws Exception {
        assertThat(post("/api/climate/rooms/2/appliances/ac-1/commands", token(1), "{\"power\":\"OFF\"}").statusCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("그래프용 값은 minutes 범위를 지킨다")
    void seriesRange() throws Exception {
        assertThat(get("/api/climate/rooms/1/series?minutes=0", token(2)).statusCode()).isEqualTo(400);
        assertThat(get("/api/climate/rooms/1/series?minutes=60", token(2)).statusCode()).isEqualTo(200);
        assertThat(get("/api/climate/rooms/9/series", token(2)).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("관리자는 방 자동 제어를 끌 수 있다")
    void autoToggle() throws Exception {
        HttpResponse<String> res = send(HttpRequest.newBuilder(uri("/api/climate/rooms/3/auto"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"enabled\":false}")), token(1));
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(get("/api/climate/rooms", token(1)).body()).contains("\"roomId\":3,\"roomName\":\"3번 방\",\"source\":\"DERIVED\",\"occupied\":null,\"auto\":false");
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET(), token);
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)), token);
    }

    private HttpResponse<String> send(HttpRequest.Builder b, String token) throws Exception {
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    static String token(long member) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .subject(Long.toString(member)).issuer("mspace-core").claim("scope", "user")
                .expirationTime(Date.from(Instant.now().plusSeconds(600))).build());
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static HttpServer fakeCore() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/api/admin/rooms/environment", ex -> {
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                int status = 403;
                try {
                    if (auth != null && "1".equals(SignedJWT.parse(auth.substring(7)).getJWTClaimsSet().getSubject())) {
                        status = 200;
                    }
                } catch (java.text.ParseException e) {
                    status = 401;
                }
                ex.sendResponseHeaders(status, -1);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
