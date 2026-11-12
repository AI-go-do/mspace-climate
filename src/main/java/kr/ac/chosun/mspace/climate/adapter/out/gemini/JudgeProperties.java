package kr.ac.chosun.mspace.climate.adapter.out.gemini;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 판단 에이전트(제미나이) 설정 climate.judge.*. 키가 비면 꺼지고 규칙만으로 켠다.
 * 무료 등급은 비서와 같은 프로젝트면 한도(분당 15·하루 500)를 나눠 쓰므로 판단 횟수를 시간·하루로 묶는다.
 * 판단 한 번에 제미나이를 보통 2~3번 부른다(기기 바꾸기 → 이유 한 문장)
 */
@ConfigurationProperties("climate.judge")
public record JudgeProperties(boolean enabled, String apiKey, String model, String baseUrl, String mcpUrl,
                              int maxPerHour, int maxPerDay, int maxTurns, Duration timeout) {

    public JudgeProperties {
        model = model == null || model.isBlank() ? "gemini-3.5-flash-lite" : model;
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://generativelanguage.googleapis.com" : baseUrl;
        mcpUrl = mcpUrl == null || mcpUrl.isBlank() ? "http://localhost:8095" : mcpUrl;
        maxPerHour = maxPerHour <= 0 ? 4 : maxPerHour;
        maxPerDay = maxPerDay <= 0 ? 40 : maxPerDay;
        maxTurns = maxTurns <= 0 ? 5 : maxTurns;
        timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
    }

    public boolean ready() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
