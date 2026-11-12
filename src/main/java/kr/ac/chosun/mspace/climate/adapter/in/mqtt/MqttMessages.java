package kr.ac.chosun.mspace.climate.adapter.in.mqtt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kr.ac.chosun.mspace.climate.application.BoardModel;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MQTT 본문 읽기. 원시 센서(ChirpStack uplink)와 보드 A 모델 결과.
 * 원시 형식은 보드 A 수집기(collector/parser.py)와 같게 읽는다: 기기 deviceInfo.devEui, 자리 deviceInfo.tags.location·spot,
 * 값 object.co2·temperature·humidity, 시각은 rxInfo[].nsTime 가운데 가장 이른 것(없으면 받은 시각).
 */
final class MqttMessages {

    record Raw(String devEui, String location, String spot, String profile, long fCnt, Instant at, Double co2,
               Double temperatureC, Double humidityPct) {

        boolean hasAir() {
            return co2 != null || temperatureC != null || humidityPct != null;
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MqttMessages() {
    }

    /** 읽을 수 없거나 기기 번호가 없으면 null */
    static Raw raw(byte[] payload, Instant received) {
        JsonNode n;
        try {
            n = JSON.readTree(payload);
        } catch (RuntimeException e) {
            return null;
        }
        JsonNode info = n.path("deviceInfo");
        String dev = info.path("devEui").asText("");
        if (dev.isBlank()) {
            return null;
        }
        Instant at = null;
        for (JsonNode rx : n.path("rxInfo")) {
            Instant t = instant(rx.path("nsTime").asText(null));
            if (t != null && (at == null || t.isBefore(at))) {
                at = t;
            }
        }
        JsonNode obj = n.path("object");
        return new Raw(dev, text(info.path("tags").path("location")), text(info.path("tags").path("spot")),
                text(info.path("deviceProfileName")), n.path("fCnt").asLong(-1), at == null ? received : at,
                num(obj.path("co2")), num(obj.path("temperature")), num(obj.path("humidity")));
    }

    /** 보드 A 공간 객체 (docs/interface.md §4). 읽을 수 없으면 null */
    static BoardModel.State inference(byte[] payload, Instant received) {
        JsonNode n;
        try {
            n = JSON.readTree(payload);
        } catch (RuntimeException e) {
            return null;
        }
        String slug = text(n.path("slug"));
        if (slug == null) {
            return null;
        }
        JsonNode fc = n.path("forecast");
        JsonNode an = n.path("anomaly");
        JsonNode st = n.path("state");
        return new BoardModel.State(slug, text(n.path("space")), instant(text(n.path("slot"))), received,
                num(fc.path("co2").path("now")), points(fc.path("co2").path("points")),
                num(fc.path("temperature").path("now")), points(fc.path("temperature").path("points")),
                num(an.path("score")), num(an.path("threshold")), an.path("is_anomaly").asBoolean(false),
                text(an.path("cause")), num(st.path("occupied_probability")),
                num(st.path("ventilation_probability")));
    }

    private static List<BoardModel.Point> points(JsonNode arr) {
        List<BoardModel.Point> out = new ArrayList<>();
        for (JsonNode p : arr) {
            Double v = num(p.path("value"));
            int m = p.path("minutes").asInt(-1);
            if (v != null && m > 0) {
                out.add(new BoardModel.Point(m, v));
            }
        }
        return out;
    }

    private static String text(JsonNode n) {
        return n.isString() && !n.asText().isBlank() ? n.asText() : null;
    }

    private static Double num(JsonNode n) {
        return n.isNumber() ? n.asDouble() : null;
    }

    private static Instant instant(String s) {
        if (s == null) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            try {
                return Instant.parse(s);
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }
}
