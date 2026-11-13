package kr.ac.chosun.mspace.climate.adapter.out;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.climate.application.StudentDesk;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 가상 학생이 core 공개 API를 부른다(도커 안쪽 core로 바로). 실제 학생과 같은 길이다.
 * <ul>
 *   <li>학생 일(예약·지금 바로 쓰기·취소·연장·퇴실·내 예약): 그 가상 학생 본인 토큰. core와 같은 JWT_SECRET으로 sub=회원 id,
 *       scope=user, 1시간짜리를 만든다. <b>설정에 적힌 가상 학생 회원 id에만</b> 만든다(다른 사람 토큰은 못 만듦)</li>
 *   <li>입실: 문 앞 QR 장치와 같은 길(POST /api/access/verify, 방마다 qr-N 장치 토큰, core MSPACE_DEVICES와 같은 값)</li>
 *   <li>방 현황: 로그인 없이 열린 GET /api/rooms/status</li>
 * </ul>
 * 토큰·학번·이름은 로그에 남기지 않는다.
 */
public class CoreStudentDesk implements StudentDesk {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(SEOUL);

    /** 방 하나의 QR 장치 (core MSPACE_DEVICES의 qr-N:N:토큰) */
    public record QrDevice(String deviceId, String token) {
        @Override
        public String toString() {
            return "QrDevice[" + deviceId + "]";
        }
    }

    private record Token(String value, Instant until) {
    }

    private final String base;
    private final byte[] secret;
    private final String issuer;
    private final java.util.Set<Long> allowed;
    private final Map<Integer, QrDevice> qr;
    private final HttpClient http;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<Long, Token> tokens = new HashMap<>();

    public CoreStudentDesk(String coreUrl, String jwtSecret, String issuer, java.util.Set<Long> allowedMembers,
                           Map<Integer, QrDevice> qr, HttpClient http, Clock clock) {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("가상 학생은 core와 같은 JWT_SECRET(32바이트 이상)이 있어야 해요");
        }
        this.base = coreUrl.replaceAll("/+$", "");
        this.secret = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
        this.allowed = java.util.Set.copyOf(allowedMembers);
        this.qr = Map.copyOf(qr);
        this.http = http;
        this.clock = clock;
    }

    /** "qr-2:2:토큰,sensor-2:2:토큰,…"(core MSPACE_DEVICES와 같은 모양)에서 방마다 첫 qr-* 장치만 고른다 */
    public static Map<Integer, QrDevice> parseQr(String spec) {
        Map<Integer, QrDevice> out = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            return out;
        }
        for (String entry : spec.split(",")) {
            String[] p = entry.trim().split(":", -1);
            if (p.length != 3 || !p[0].trim().startsWith("qr-") || p[2].isBlank()) {
                continue;
            }
            try {
                out.putIfAbsent(Integer.parseInt(p[1].trim()), new QrDevice(p[0].trim(), p[2].trim()));
            } catch (NumberFormatException e) {
                // 건너뜀
            }
        }
        return out;
    }

    /** "회원id:학번,회원id:학번,…" → 가상 학생 목록. 형식이 틀린 칸은 건너뛴다 */
    public static List<Student> parseStudents(String spec) {
        List<Student> out = new ArrayList<>();
        if (spec == null || spec.isBlank()) {
            return out;
        }
        for (String entry : spec.split(",")) {
            String[] p = entry.trim().split(":", -1);
            if (p.length != 2 || p[1].isBlank()) {
                continue;
            }
            try {
                out.add(new Student(Long.parseLong(p[0].trim()), p[1].trim()));
            } catch (NumberFormatException e) {
                // 건너뜀
            }
        }
        return out;
    }

    public java.util.Set<Integer> qrRooms() {
        return qr.keySet();
    }

    @Override
    public Booked book(Student owner, int roomId, Instant startAt, Instant endAt, List<String> participants, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roomId", roomId);
        body.put("startAt", AT.format(startAt));
        body.put("endAt", AT.format(endAt));
        body.put("participantStudentIds", participants);
        return booked(send(owner, "POST", "/api/reservations", body, key));
    }

    @Override
    public Booked startNow(Student owner, int roomId, Instant endAt, List<String> participants, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roomId", roomId);
        body.put("endAt", AT.format(endAt));
        body.put("participantStudentIds", participants);
        return booked(send(owner, "POST", "/api/reservations/instant", body, key));
    }

    @Override
    public void cancel(Student owner, long reservationId) {
        send(owner, "POST", "/api/reservations/" + reservationId + "/cancel", Map.of(), null);
    }

    @Override
    public boolean checkIn(int roomId, String accessCode, String eventId) {
        QrDevice d = qr.get(roomId);
        if (d == null) {
            throw new IllegalStateException("방 " + roomId + " QR 장치 토큰 없음");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId.length() > 64 ? eventId.substring(0, 64) : eventId);
        body.put("roomId", roomId);
        body.put("deviceId", d.deviceId());
        body.put("code", accessCode);
        JsonNode res = call(HttpRequest.newBuilder(URI.create(base + "/api/access/verify"))
                .header("Authorization", "Bearer " + d.token()), "POST", body);
        return "OPENED".equals(res.path("result").asText(""));
    }

    @Override
    public void checkout(Student owner, long reservationId) {
        send(owner, "POST", "/api/reservations/" + reservationId + "/checkout", Map.of(), null);
    }

    @Override
    public Booked extend(Student owner, long reservationId, Instant endAt, String key) {
        return booked(send(owner, "POST", "/api/reservations/" + reservationId + "/continue",
                Map.of("endAt", AT.format(endAt)), key));
    }

    @Override
    public List<Booked> active(Student owner) {
        List<Booked> out = new ArrayList<>();
        for (JsonNode r : send(owner, "GET", "/api/reservations/me", null, null)) {
            String status = r.path("status").asText("");
            if (!r.path("owner").asBoolean(false) || !("BOOKED".equals(status) || "IN_USE".equals(status))) {
                continue;
            }
            out.add(new Booked(r.path("id").asLong(), r.path("room").path("id").asInt(), instant(r.path("startAt")),
                    instant(r.path("endAt")), status, text(r.path("accessCode")), r.path("participants").size()));
        }
        return out;
    }

    @Override
    public Map<Integer, RoomNow> rooms() {
        JsonNode res = call(HttpRequest.newBuilder(URI.create(base + "/api/rooms/status")), "GET", null);
        Map<Integer, RoomNow> out = new HashMap<>();
        for (JsonNode r : res.path("rooms")) {
            out.put(r.path("roomId").asInt(), new RoomNow(r.path("status").asText(""), instant(r.path("currentEndAt"))));
        }
        return out;
    }

    // ---- 보내기

    private JsonNode send(Student who, String method, String path, Object body, String key) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token(who.memberId()));
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        return call(b, method, body);
    }

    private JsonNode call(HttpRequest.Builder b, String method, Object body) {
        try {
            b.timeout(Duration.ofSeconds(5));
            if ("GET".equals(method)) {
                b.GET();
            } else {
                b.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body == null ? Map.of() : body)));
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            JsonNode node = res.body() == null || res.body().isBlank() ? json.createObjectNode() : json.readTree(res.body());
            if (code >= 200 && code < 300) {
                return node;
            }
            if (code >= 400 && code < 500) {
                throw new Refused(code, node.path("code").asText("HTTP_" + code));
            }
            throw new IllegalStateException("core 응답 " + code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("중단됨", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("core 연결 실패", e);
        }
    }

    /** 가상 학생 본인 토큰(1시간, 50분 동안 다시 씀). 설정에 없는 회원이면 만들지 않는다 */
    synchronized String token(long memberId) {
        if (!allowed.contains(memberId)) {
            throw new IllegalArgumentException("가상 학생이 아닌 회원 토큰은 만들지 않아요");
        }
        Instant now = clock.instant();
        Token t = tokens.get(memberId);
        if (t != null && now.isBefore(t.until())) {
            return t.value();
        }
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(Long.toString(memberId))
                .claim("scope", "user")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(secret));
        } catch (Exception e) {
            throw new IllegalStateException("토큰 서명 실패", e);
        }
        String value = jwt.serialize();
        tokens.put(memberId, new Token(value, now.plus(Duration.ofMinutes(50))));
        return value;
    }

    private static Booked booked(JsonNode r) {
        return new Booked(r.path("id").asLong(), r.path("roomId").asInt(), instant(r.path("startAt")),
                instant(r.path("endAt")), r.path("status").asText(""), text(r.path("accessCode")),
                r.path("headcount").asInt(0));
    }

    private static Instant instant(JsonNode n) {
        String s = text(n);
        if (s == null || s.isBlank()) {
            return null;
        }
        int zone = s.indexOf('[');   // "…+09:00[Asia/Seoul]" 모양이어도 읽게
        return OffsetDateTime.parse(zone > 0 ? s.substring(0, zone) : s).toInstant();
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? null : n.asText();
    }
}
