package kr.ac.chosun.mspace.climate.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.chosun.mspace.climate.application.StudentDesk.Booked;
import kr.ac.chosun.mspace.climate.application.StudentDesk.Refused;
import kr.ac.chosun.mspace.climate.application.StudentDesk.Student;
import kr.ac.chosun.mspace.climate.auth.JwtVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 가상 학생이 core를 부르는 모양: 본인 토큰·QR 장치 토큰, 가상 학생이 아닌 회원 토큰은 안 만듦 */
class CoreStudentDeskTest {

    static final String SECRET = "test-secret-test-secret-test-secret-123456";
    static final Student SIM = new Student(41, "99980001");

    HttpServer server;
    final List<String[]> seen = new ArrayList<>();   // {길, Authorization, 본문}
    int status = 201;
    String reply = "{}";
    CoreStudentDesk desk;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            seen.add(new String[] {ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("Authorization"),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)});
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        desk = new CoreStudentDesk("http://127.0.0.1:" + server.getAddress().getPort(), SECRET, "mspace-core", Set.of(41L),
                CoreStudentDesk.parseQr("sensor-2:2:sss,qr-2:2:qqq,keypad-2:2:kkk,qr-x:y:bad"), HttpClient.newHttpClient(),
                Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("예약은 그 가상 학생 본인 토큰(core와 같은 서명, sub=회원 id)으로 보내고 응답의 입실 번호를 읽는다")
    void booksWithOwnToken() {
        // given
        reply = """
                {"id":7,"roomId":2,"startAt":"2026-11-14T14:00:00+09:00","endAt":"2026-11-14T15:30:00+09:00",
                 "status":"BOOKED","headcount":3,"accessCode":"123456","qrText":"MSPACE:2:123456"}""";
        // when
        Booked b = desk.book(SIM, 2, Instant.parse("2026-11-14T05:00:00Z"), Instant.parse("2026-11-14T06:30:00Z"),
                List.of("99980002", "99980003"), "sim-1");
        // then
        assertThat(b.id()).isEqualTo(7);
        assertThat(b.accessCode()).isEqualTo("123456");
        assertThat(b.endAt()).isEqualTo(Instant.parse("2026-11-14T06:30:00Z"));
        String[] req = seen.get(0);
        assertThat(req[0]).isEqualTo("/api/reservations");
        assertThat(new JwtVerifier(SECRET, "mspace-core", Clock.systemUTC()).memberId(req[1])).isEqualTo(41);
        assertThat(req[2]).contains("\"startAt\":\"2026-11-14T14:00:00+09:00\"").contains("99980002");
    }

    @Test
    @DisplayName("입실은 그 방 QR 장치 토큰으로 보내고, OPENED일 때만 열린 것으로 본다")
    void checksInWithQrDevice() {
        // given
        status = 200;
        reply = "{\"result\":\"OPENED\"}";
        // when
        boolean opened = desk.checkIn(2, "123456", "sim-qr-7");
        reply = "{\"result\":\"DENIED\",\"reason\":\"NOT_YET\"}";
        boolean denied = desk.checkIn(2, "123456", "sim-qr-7b");
        // then
        assertThat(opened).isTrue();
        assertThat(denied).isFalse();
        assertThat(seen.get(0)[0]).isEqualTo("/api/access/verify");
        assertThat(seen.get(0)[1]).isEqualTo("Bearer qqq");
        assertThat(seen.get(0)[2]).contains("\"deviceId\":\"qr-2\"").contains("\"code\":\"123456\"");
    }

    @Test
    @DisplayName("core가 거절하면(409) 오류 코드를 그대로 알린다")
    void refusedCarriesCode() {
        // given
        status = 409;
        reply = "{\"code\":\"RESERVATION_TIME_TAKEN\",\"message\":\"이미 예약된 시간입니다\"}";
        // when · then
        assertThatThrownBy(() -> desk.startNow(SIM, 2, Instant.parse("2026-11-14T06:30:00Z"), List.of("99980002"), "k"))
                .isInstanceOfSatisfying(Refused.class, e -> assertThat(e.code()).isEqualTo("RESERVATION_TIME_TAKEN"));
    }

    @Test
    @DisplayName("가상 학생 목록에 없는 회원의 토큰은 만들지 않는다(실제 학생 행세 금지)")
    void neverMintsForRealMembers() {
        assertThatThrownBy(() -> desk.cancel(new Student(1, "20230001"), 5)).isInstanceOf(IllegalArgumentException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("설정 글자에서 가상 학생·QR 장치만 고른다(틀린 칸은 건너뜀)")
    void parsesSettings() {
        assertThat(CoreStudentDesk.parseStudents("41:99980001, 42:99980002,bad,x:1")).containsExactly(
                new Student(41, "99980001"), new Student(42, "99980002"));
        assertThat(CoreStudentDesk.parseQr("sensor-2:2:sss,qr-2:2:qqq,qr-3:3:rrr")).containsOnlyKeys(2, 3);
        assertThat(Map.copyOf(CoreStudentDesk.parseQr(""))).isEmpty();
    }
}
