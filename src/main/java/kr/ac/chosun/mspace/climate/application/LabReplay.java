package kr.ac.chosun.mspace.climate.application;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * 실습실 센서가 멈췄을 때 쓰는 녹화 재생 (재웅 11/12: 11/8부터 실습실 LoRa 업링크 0건).
 * 실습실에서 실제로 잰 1분 기록(10/21~11/8)을 "지금과 같은 요일·같은 시각"(정확히 N주 전)으로 맞춰 다시 낸다.
 * 수업 시간에 오르고 밤에 내려가는 실제 모양이 그대로 나온다. 화면·API에는 source=REPLAY로 밝힌다.
 * 센서마다 보내는 간격(1·2·10분)이 달라서, 그 분에 값이 없으면 앞 GAP분 안의 마지막 값을 쓴다.
 */
public class LabReplay {

    static final Duration WEEK = Duration.ofDays(7);
    static final int GAP_MINUTES = 10;

    /** 기록 한 줄 */
    public record Row(Integer ppm, Double temperatureC, Double humidityPct) {
    }

    private final Map<String, NavigableMap<Long, Row>> bySpot;
    private final long firstMinute;
    private final long lastMinute;

    LabReplay(Map<String, NavigableMap<Long, Row>> bySpot) {
        this.bySpot = bySpot;
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (NavigableMap<Long, Row> m : bySpot.values()) {
            if (!m.isEmpty()) {
                first = Math.min(first, m.firstKey());
                last = Math.max(last, m.lastKey());
            }
        }
        this.firstMinute = first;
        this.lastMinute = last;
    }

    /** gzip CSV(minute_utc,spot,co2,temperature,humidity)를 읽는다 */
    public static LabReplay load(InputStream gz) throws IOException {
        Map<String, NavigableMap<Long, Row>> by = new HashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(gz), StandardCharsets.UTF_8))) {
            String line = in.readLine();
            while ((line = in.readLine()) != null) {
                String[] f = line.split(",", -1);
                if (f.length < 5 || f[2].isBlank()) {
                    continue;
                }
                long minute = LocalDateTime.parse(f[0]).toEpochSecond(ZoneOffset.UTC) / 60;
                Row row = new Row((int) Math.round(Double.parseDouble(f[2])), num(f[3]), num(f[4]));
                by.computeIfAbsent(f[1], k -> new TreeMap<>()).put(minute, row);
            }
        }
        return new LabReplay(by);
    }

    public boolean isEmpty() {
        return bySpot.isEmpty();
    }

    /** now에 틀 기록 시각: 기록 안에 들어오는 가장 가까운 N주 전(N≥1) */
    long sourceMinute(Instant now) {
        long minute = now.getEpochSecond() / 60;
        long week = WEEK.toMinutes();
        long n = Math.max(1, (minute - lastMinute + week - 1) / week);
        long src = minute - n * week;
        return src < firstMinute ? Long.MIN_VALUE : src;
    }

    /**
     * now 분에 낼 값들. spots: 자리 이름 → 방 번호. 시각은 지금 분으로 바꾸고, eventId로 같은 분 두 번 내기를 막는다
     */
    public List<SensorReading> at(Instant now, Map<String, Integer> spots) {
        List<SensorReading> out = new ArrayList<>();
        long src = sourceMinute(now);
        if (src == Long.MIN_VALUE || spots == null) {
            return out;
        }
        Instant at = Instant.ofEpochSecond(now.getEpochSecond() / 60 * 60);
        spots.forEach((spot, room) -> {
            NavigableMap<Long, Row> m = bySpot.get(spot);
            var e = m == null ? null : m.floorEntry(src);
            if (e == null || src - e.getKey() > GAP_MINUTES) {
                return;
            }
            Row r = e.getValue();
            out.add(new SensorReading("replay-" + room + "-" + at.getEpochSecond() / 60, room, "CO2", null, r.ppm(),
                    r.temperatureC(), r.humidityPct(), at));
        });
        return out;
    }

    private static Double num(String s) {
        return s == null || s.isBlank() ? null : Double.valueOf(s);
    }
}
