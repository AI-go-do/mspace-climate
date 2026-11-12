package kr.ac.chosun.mspace.climate.adapter.out.gemini;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 판단 에이전트(제미나이) 설정 climate.judge.*. 키가 비면 꺼지고 규칙만으로 켠다.
 * 무료 등급은 비서와 같은 프로젝트면 한도(분당 15·하루 500)를 나눠 쓰므로 판단 횟수를 시간·하루로 묶는다.
 * 판단 한 번에 제미나이를 보통 2~3번 부른다(기기 바꾸기 → 이유 한 문장).
 * 판단이 몰려도 분당 한도를 같이 쓰는 비서·core를 막지 않게, 제미나이 호출 자체를 1분에 maxCallsPerMinute번까지로 묶는다.
 * 적당히 부르기(재웅 11/13): 같은 방은 roomCooldown에 한 번만 묻고, priorityRooms(실제 방) 밖의 방(가상 손님 방)은
 * 시간당 maxPerHourOthers번까지만 써서 실제 방 몫을 남긴다. 하루 한도는 시간당 한도 × 24보다 크게 둬 발표 전에 바닥나지 않게
 */
@ConfigurationProperties("climate.judge")
public record JudgeProperties(boolean enabled, String apiKey, String model, String baseUrl, String mcpUrl,
                              int maxPerHour, int maxPerDay, int maxTurns, Duration timeout,
                              int maxCallsPerMinute, Duration roomCooldown, List<Integer> priorityRooms,
                              int maxPerHourOthers) {

    public JudgeProperties {
        model = model == null || model.isBlank() ? "gemini-3.5-flash-lite" : model;
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://generativelanguage.googleapis.com" : baseUrl;
        mcpUrl = mcpUrl == null || mcpUrl.isBlank() ? "http://localhost:8095" : mcpUrl;
        maxPerHour = maxPerHour <= 0 ? 4 : maxPerHour;
        maxPerDay = maxPerDay <= 0 ? 40 : maxPerDay;
        maxTurns = maxTurns <= 0 ? 5 : maxTurns;
        timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
        maxCallsPerMinute = maxCallsPerMinute <= 0 ? 5 : maxCallsPerMinute;
        roomCooldown = roomCooldown == null ? Duration.ofMinutes(60) : roomCooldown;
        priorityRooms = priorityRooms == null ? List.of() : List.copyOf(priorityRooms);
        maxPerHourOthers = maxPerHourOthers <= 0 ? maxPerHour : maxPerHourOthers;
    }

    public boolean ready() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
