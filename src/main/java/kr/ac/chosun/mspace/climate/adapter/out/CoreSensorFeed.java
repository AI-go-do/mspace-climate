package kr.ac.chosun.mspace.climate.adapter.out;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import kr.ac.chosun.mspace.climate.adapter.in.scheduler.SimFeeder;
import kr.ac.chosun.mspace.climate.application.RoomSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시뮬레이션 값을 core에도 보낸다 (POST {core}/api/sensor-events, 실제 센서와 같은 모양·방마다 장치 토큰, 지영 11/13 부탁).
 * core 자동 처리(미리 환기·더움·시간 넘김·센서 고장·예약 없는 사용)가 시연 중에도 돌게 하려는 것.
 * <ul>
 *   <li>CO2(+온도·습도): 방마다 1분 평균을 1분에 한 번, source=SIM. eventId "sim-co2-방-yyyyMMddHHmm"(같은 분은 한 번만)</li>
 *   <li>재실(OCCUPANCY): 가상 방(2~5번)만. 입실·퇴실이 바뀌면 바로, 그대로면 occupancyEvery마다 한 번.
 *       core는 재실 신호가 1분 안 오면 끊김(센서 고장)으로 보고 "있음"으로 치므로 살아 있다는 신호가 필요하다.
 *       core는 상태가 그대로인 신호는 DB(sensor_event)에 남기지 않고 받은 시각만 고친다 → DB는 입실·퇴실 때만 늘어난다</li>
 *   <li>실제 값이 오는 방(climate가 시뮬레이션 값을 안 쓴 방)은 CO2를 보내지 않는다</li>
 *   <li>보내기는 따로 스레드에서. core가 꺼져 있거나 실패해도 climate는 그대로 돌고 로그만 남긴다(방 번호·종류·상태만, 토큰 없음)</li>
 * </ul>
 */
public class CoreSensorFeed implements SimFeeder.Outbound, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CoreSensorFeed.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(SEOUL);
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(SEOUL);

    /** 방 하나의 센서 장치 (core MSPACE_DEVICES의 sensor-N:N:토큰) */
    public record Device(String deviceId, String token) {
        @Override
        public String toString() {
            return "Device[" + deviceId + "]";   // 토큰이 로그에 찍히지 않게
        }
    }

    private static final class Bucket {
        final long minute;
        double co2;
        double temp;
        double hum;
        int n;

        Bucket(long minute) {
            this.minute = minute;
        }
    }

    private final URI endpoint;
    private final Map<Integer, Device> devices;
    private final Duration occupancyEvery;
    private final HttpClient http;
    private final Executor worker;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<Integer, Bucket> buckets = new HashMap<>();
    private final Map<Integer, Boolean> lastOccupied = new HashMap<>();
    private final Map<Integer, Instant> lastOccupiedAt = new HashMap<>();
    private volatile Boolean failing;

    public CoreSensorFeed(String coreUrl, Map<Integer, Device> devices, Duration occupancyEvery, HttpClient http) {
        this(coreUrl, devices, occupancyEvery, http, defaultWorker());
    }

    CoreSensorFeed(String coreUrl, Map<Integer, Device> devices, Duration occupancyEvery, HttpClient http, Executor worker) {
        this.endpoint = URI.create(coreUrl.replaceAll("/+$", "") + "/api/sensor-events");
        this.devices = Map.copyOf(devices);
        this.occupancyEvery = occupancyEvery;
        this.http = http;
        this.worker = worker;
    }

    /** core가 느려도 쌓이지 않게: 한 줄로 보내고, 200건 넘게 밀리면 오래된 것부터 버린다 */
    private static ExecutorService defaultWorker() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(200), r -> {
            Thread t = new Thread(r, "climate-core-feed");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    /**
     * "sensor-2:2:토큰,keypad-2:2:토큰,…"(core MSPACE_DEVICES와 같은 모양)에서 방마다 첫 sensor-* 장치만 고른다.
     * 형식이 틀린 칸은 건너뛴다(climate는 core가 아니라 띄우는 것까지 막지 않는다)
     */
    public static Map<Integer, Device> parse(String spec) {
        Map<Integer, Device> out = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            return out;
        }
        for (String entry : spec.split(",")) {
            String[] p = entry.trim().split(":", -1);
            if (p.length != 3 || !p[0].trim().startsWith("sensor-") || p[2].isBlank()) {
                continue;
            }
            try {
                out.putIfAbsent(Integer.parseInt(p[1].trim()), new Device(p[0].trim(), p[2].trim()));
            } catch (NumberFormatException e) {
                // 방 번호가 숫자가 아니면 건너뜀
            }
        }
        return out;
    }

    public java.util.Set<Integer> rooms() {
        return devices.keySet();
    }

    @Override
    public synchronized void simulated(RoomSimulator.State s, Instant at, boolean accepted, boolean virtual) {
        Device d = devices.get(s.roomId());
        if (d == null) {
            return;
        }
        long minute = at.getEpochSecond() / 60;
        Bucket b = buckets.get(s.roomId());
        if (b != null && b.minute != minute) {
            buckets.remove(s.roomId());
            if (b.n > 0) {
                sendCo2(s.roomId(), d, b);
            }
            b = null;
        }
        if (accepted) {
            if (b == null) {
                b = new Bucket(minute);
                buckets.put(s.roomId(), b);
            }
            b.co2 += s.co2();
            b.temp += s.temperatureC();
            b.hum += s.humidityPct();
            b.n++;
        }
        if (virtual) {
            Boolean before = lastOccupied.get(s.roomId());
            Instant sentAt = lastOccupiedAt.get(s.roomId());
            if (before == null || before != s.occupied() || sentAt == null
                    || Duration.between(sentAt, at).compareTo(occupancyEvery) >= 0) {
                lastOccupied.put(s.roomId(), s.occupied());
                lastOccupiedAt.put(s.roomId(), at);
                sendOccupancy(s.roomId(), d, s.occupied(), at);
            }
        }
    }

    private void sendCo2(int room, Device d, Bucket b) {
        Instant start = Instant.ofEpochSecond(b.minute * 60);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", "sim-co2-%d-%s".formatted(room, MINUTE.format(start)));
        body.put("roomId", room);
        body.put("deviceId", d.deviceId());
        body.put("kind", "CO2");
        body.put("ppm", (int) Math.round(b.co2 / b.n));
        body.put("source", "SIM");
        body.put("temperatureC", Math.round(b.temp / b.n * 10) / 10.0);
        body.put("humidityPct", Math.round(b.hum / b.n * 10) / 10.0);
        body.put("observedAt", AT.format(start));
        submit(room, "CO2", d, body);
    }

    private void sendOccupancy(int room, Device d, boolean occupied, Instant at) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", "sim-occ-%d-%d".formatted(room, at.getEpochSecond()));
        body.put("roomId", room);
        body.put("deviceId", d.deviceId());
        body.put("kind", "OCCUPANCY");
        body.put("occupied", occupied);
        body.put("sources", Map.of("sim", true));
        body.put("observedAt", AT.format(at));
        submit(room, "OCCUPANCY", d, body);
    }

    private void submit(int room, String kind, Device d, Map<String, Object> body) {
        try {
            worker.execute(() -> post(room, kind, d, body));
        } catch (RuntimeException e) {
            log.warn("core 센서 보내기 대기열 실패 방={} 종류={} ({})", room, kind, e.getClass().getSimpleName());
        }
    }

    private void post(int room, String kind, Device d, Map<String, Object> body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + d.token())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                ok();
            } else {
                fail("방=%d 종류=%s 상태=%d".formatted(room, kind, res.statusCode()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            fail("방=%d 종류=%s (%s)".formatted(room, kind, e.getClass().getSimpleName()));
        }
    }

    /** 실패가 이어지면 처음 한 번만 경고, 다시 되면 한 번 알림(core가 꺼져 있을 때 로그가 넘치지 않게) */
    private void fail(String what) {
        if (!Boolean.TRUE.equals(failing)) {
            log.warn("core로 시뮬레이션 센서 값 보내기 실패 {} (다시 될 때까지 경고 안 함)", what);
        }
        failing = true;
    }

    private void ok() {
        if (failing == null || failing) {
            log.info("core로 시뮬레이션 센서 값 보내는 중 (방 {})", devices.keySet());
        }
        failing = false;
    }

    @Override
    public void close() {
        if (worker instanceof ExecutorService e) {
            e.shutdownNow();
        }
    }
}
