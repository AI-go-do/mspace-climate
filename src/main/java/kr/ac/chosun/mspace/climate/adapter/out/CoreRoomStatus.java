package kr.ac.chosun.mspace.climate.adapter.out;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import kr.ac.chosun.mspace.climate.application.ClimateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 재실은 core 방 현황(GET /api/rooms/status, 로그인 없이 열림)에서 읽는다. core가 이미 센서·예약을 합쳐 판단하기 때문.
 * presence OCCUPIED/EMPTY면 그대로, UNKNOWN(센서 없음)이면 예약이 사용 중(IN_USE)일 때 사람이 있는 것으로 본다.
 * 시뮬레이션이 손님을 만드는 가상 방(skip)은 건너뛴다(재실은 시뮬레이션이 정함).
 */
public class CoreRoomStatus {

    private static final Logger log = LoggerFactory.getLogger(CoreRoomStatus.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final URI uri;
    private final Clock clock;
    private final java.util.Set<Integer> skip;

    public CoreRoomStatus(String coreUri, Clock clock) {
        this(coreUri, clock, java.util.Set.of());
    }

    /** skip: core 재실을 쓰지 않는 방(시뮬레이션 가상 방) */
    public CoreRoomStatus(String coreUri, Clock clock, java.util.Set<Integer> skip) {
        this.uri = URI.create(coreUri + "/api/rooms/status");
        this.clock = clock;
        this.skip = java.util.Set.copyOf(skip);
    }

    public void pull(ClimateStore store) {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("core 방 현황 응답 {}", res.statusCode());
                return;
            }
            apply(json.readTree(res.body()), store);
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("core 방 현황 실패: {}", e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void apply(JsonNode body, ClimateStore store) {
        for (JsonNode r : body.path("rooms")) {
            int id = r.path("roomId").asInt(-1);
            if (skip.contains(id)) {
                continue;
            }
            String presence = r.path("presence").asText("UNKNOWN");
            Boolean occupied = switch (presence) {
                case "OCCUPIED" -> true;
                case "EMPTY" -> false;
                default -> "IN_USE".equals(r.path("status").asText("")) ? Boolean.TRUE : null;
            };
            store.setOccupancy(id, occupied, clock.instant());
        }
    }
}
