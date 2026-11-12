package kr.ac.chosun.mspace.climate.adapter.out.gemini;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import kr.ac.chosun.mspace.climate.application.Judge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 판단 에이전트 = 제미나이 + climate MCP 도구 (재웅 11/12 "climate가 MCP 서버, 판단 에이전트는 MCP로 도구를 골라 씀").
 * 비서(영준)와 같은 구조: MCP 클라이언트로 climate 자기 /mcp에 붙어 도구 목록을 받고, 그 스키마를 그대로 제미나이 함수 선언으로 넘긴다.
 * 모델이 도구를 부르면 MCP로 실행해 결과를 돌려주고, 도구 호출이 없을 때까지 반복(최대 maxTurns).
 *
 * <p>한 번에 한 방씩(스레드 하나), 시간·하루 횟수 한도. 한도·키 오류·시간 초과면 fallback(규칙이 켬).
 * 제미나이에 보내는 것은 방 번호·이름·공기 값·기기 상태뿐이다(사람 이름·학번 없음). 로그엔 방 번호·걸린 시간·성공 여부만
 */
public class GeminiJudge implements Judge, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GeminiJudge.class);

    static final String SYSTEM = """
            너는 대학 스터디룸(M-space) 공기를 돌보는 AI "오디"다. 방 하나에 새 이상이 생겼다.
            room_status 결과를 보고, 필요한 기기만 도구로 바꿔라. 규칙:
            - CO2가 높으면 환풍기를 켠다. 공기청정기는 CO2를 못 낮춘다(환기 때 들어오는 먼지용으로 함께 켜도 된다).
            - 온도가 높으면 냉방, 낮으면 난방. 목표 온도는 coolTargetRangeC·heatTargetRangeC 안에서 이 방 평소 평균(usualMean)에 가깝게.
            - 습도가 높고 냉난방 중이 아니면 제습(DRY).
            - 이미 맞게 켜진 기기는 다시 켜지 않는다. 필요 없는 기기는 켜지 않는다.
            - 도구가 ok=false로 거절하면 그 이유를 받아들이고 다시 시도하지 않는다.
            끝나면 도구 없이, 무엇을 왜 했는지 한국어 해요체 한 문장(40자 안팎)으로만 답한다. 숫자는 근거로 하나만.""";

    private final JudgeProperties props;
    private final JsonMapper json;
    private final Clock clock;
    private final RestClient rest;
    private final ExecutorService worker;
    private final Deque<Ask> asked = new ArrayDeque<>();

    private record Ask(Instant at, int roomId) {
    }
    /** 지난 1분 동안 제미나이를 부른 시각(판단 한 번에 여러 번) */
    private final Deque<Instant> calls = new ArrayDeque<>();

    public GeminiJudge(JudgeProperties props, JsonMapper json, Clock clock) {
        this.props = props;
        this.json = json;
        this.clock = clock;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(props.timeout()).build());
        factory.setReadTimeout(props.timeout());
        this.rest = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "climate-judge");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public boolean enabled() {
        return props.ready();
    }

    @Override
    public void ask(Request r, Listener l) {
        String over = take(r.roomId());
        if (over != null) {
            l.fallback(r, over);
            return;
        }
        worker.submit(() -> run(r, l));
    }

    /** 한도 안이면 한 번 쓰고 null, 넘었으면 이유(화면에 "규칙으로 판단(이유)") */
    synchronized String take(int roomId) {
        Instant now = clock.instant();
        while (!asked.isEmpty() && asked.peekFirst().at().isBefore(now.minus(Duration.ofDays(1)))) {
            asked.pollFirst();
        }
        // 이번 판단이 보통 쓰는 2번이 1분 한도 안에 들어가야 시작한다
        if (recentCalls(now) + 2 > props.maxCallsPerMinute()) {
            return "제미나이 분당 %d번 한도".formatted(props.maxCallsPerMinute());
        }
        Instant cool = now.minus(props.roomCooldown());
        if (asked.stream().anyMatch(x -> x.roomId() == roomId && x.at().isAfter(cool))) {
            return "같은 방은 %d분에 한 번만 제미나이".formatted(props.roomCooldown().toMinutes());
        }
        Instant hourAgo = now.minus(Duration.ofHours(1));
        long hour = asked.stream().filter(x -> x.at().isAfter(hourAgo)).count();
        if (hour >= props.maxPerHour()) {
            return "제미나이 시간당 %d번 한도".formatted(props.maxPerHour());
        }
        boolean other = !props.priorityRooms().isEmpty() && !props.priorityRooms().contains(roomId);
        if (other && asked.stream().filter(x -> x.at().isAfter(hourAgo) && !props.priorityRooms().contains(x.roomId()))
                .count() >= props.maxPerHourOthers()) {
            return "다른 방은 시간당 %d번만 제미나이(실제 방 몫)".formatted(props.maxPerHourOthers());
        }
        if (asked.size() >= props.maxPerDay()) {
            return "제미나이 하루 %d번 한도".formatted(props.maxPerDay());
        }
        asked.addLast(new Ask(now, roomId));
        return null;
    }

    /** 제미나이 한 번 부르기 전에. 1분 한도 안이면 null, 넘었으면 이유(판단은 규칙에게 넘어간다) */
    synchronized String callSlot() {
        Instant now = clock.instant();
        if (recentCalls(now) >= props.maxCallsPerMinute()) {
            return "제미나이 분당 %d번 한도".formatted(props.maxCallsPerMinute());
        }
        calls.addLast(now);
        return null;
    }

    private int recentCalls(Instant now) {
        while (!calls.isEmpty() && !calls.peekFirst().isAfter(now.minus(Duration.ofMinutes(1)))) {
            calls.pollFirst();
        }
        return calls.size();
    }

    private void run(Request r, Listener l) {
        long started = System.nanoTime();
        boolean ok = false;
        try {
            Outcome o = judge(r, l);
            l.done(r, o.did(), o.reason());
            ok = true;
        } catch (Failure e) {
            l.fallback(r, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("judge 방 {} 실패: {}", r.roomId(), e.getClass().getSimpleName());
            l.fallback(r, "제미나이 응답 없음");
        } finally {
            log.info("judge 방 {} ok={} {}ms", r.roomId(), ok, (System.nanoTime() - started) / 1_000_000);
        }
    }

    record Outcome(List<String> did, String reason) {
    }

    /** 사람에게 보일 짧은 실패 이유 */
    static final class Failure extends RuntimeException {
        Failure(String message) {
            super(message, null, false, false);
        }
    }

    Outcome judge(Request r, Listener l) {
        var transport = HttpClientStreamableHttpTransport.builder(props.mcpUrl()).build();
        McpSyncClient mcp = McpClient.sync(transport).requestTimeout(props.timeout()).build();
        try {
            mcp.initialize();
            List<Map<String, Object>> declarations = mcp.listTools().tools().stream().map(t -> {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("name", t.name());
                d.put("description", t.description());
                d.put("parametersJsonSchema", json.convertValue(t.inputSchema(), Map.class));
                return d;
            }).toList();
            l.progress(r, "방 상태 보는 중 (room_status)");
            String status = call(mcp, "room_status", Map.of("roomId", r.roomId())).text();
            List<Map<String, Object>> contents = new ArrayList<>();
            contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", prompt(r, status)))));
            List<String> did = new ArrayList<>();
            for (int turn = 0; turn < props.maxTurns(); turn++) {
                Map<String, Object> content = generate(contents, declarations);
                contents.add(content);
                List<Map<String, Object>> calls = calls(content);
                if (calls.isEmpty()) {
                    return new Outcome(did, text(content));
                }
                List<Map<String, Object>> answers = new ArrayList<>();
                for (Map<String, Object> fc : calls) {
                    String name = String.valueOf(fc.get("name"));
                    Map<String, Object> args = asMap(fc.get("args"));
                    l.progress(r, doing(name, args));
                    Called c = call(mcp, name, args);
                    Object parsed = parse(c.text());
                    if (!c.error() && name.startsWith("set_") && parsed instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("ok"))) {
                        did.add(String.valueOf(m.get("text")));
                    }
                    Map<String, Object> fr = new LinkedHashMap<>();
                    if (fc.get("id") != null) {
                        fr.put("id", fc.get("id"));
                    }
                    fr.put("name", name);
                    fr.put("response", Map.of(c.error() ? "error" : "result", parsed));
                    answers.add(Map.of("functionResponse", fr));
                }
                contents.add(Map.of("role", "user", "parts", answers));
            }
            return new Outcome(did, did.isEmpty() ? "도구를 여러 번 불러 멈췄어요" : null);
        } finally {
            mcp.closeGracefully();
        }
    }

    static String prompt(Request r, String status) {
        return "%d번 방(%s)에 새 이상이 생겼어요.\n%s\n\nroom_status 결과:\n%s".formatted(
                r.roomId(), r.roomName(), String.join("\n", r.findings()), status);
    }

    /** 화면의 "제어" 단계에 보일 "…하는 중" */
    static String doing(String name, Map<String, Object> args) {
        return switch (name) {
            case "room_status" -> "방 상태 다시 보는 중";
            case "set_aircon" -> switch (String.valueOf(args.get("mode")).toUpperCase()) {
                case "COOL" -> "에어컨 냉방 %s°C로 켜는 중".formatted(args.get("targetC"));
                case "HEAT" -> "에어컨 난방 %s°C로 켜는 중".formatted(args.get("targetC"));
                case "DRY" -> "에어컨 제습 켜는 중";
                default -> "에어컨 끄는 중";
            };
            case "set_ventilator" -> Boolean.TRUE.equals(args.get("on")) ? "환풍기 켜는 중" : "환풍기 끄는 중";
            case "set_purifier" -> Boolean.TRUE.equals(args.get("on")) ? "공기청정기 켜는 중" : "공기청정기 끄는 중";
            default -> name + " 부르는 중";
        };
    }

    record Called(String text, boolean error) {
    }

    private static Called call(McpSyncClient mcp, String name, Map<String, Object> args) {
        try {
            CallToolResult result = mcp.callTool(CallToolRequest.builder(name).arguments(args).build());
            String text = result.content().stream().filter(TextContent.class::isInstance)
                    .map(c -> ((TextContent) c).text()).collect(Collectors.joining("\n"));
            return new Called(text, Boolean.TRUE.equals(result.isError()));
        } catch (RuntimeException e) {
            return new Called("{\"message\":\"도구를 실행하지 못했어요\"}", true);
        }
    }

    private Object parse(String text) {
        try {
            return json.readValue(text, Object.class);
        } catch (RuntimeException e) {
            return text;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** 제미나이 한 번. 받은 content(생각 서명 포함)를 그대로 돌려줘 다음 요청에 그대로 붙인다 */
    @SuppressWarnings("unchecked")
    Map<String, Object> generate(List<Map<String, Object>> contents, List<Map<String, Object>> declarations) {
        String over = callSlot();
        if (over != null) {
            throw new Failure(over);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", SYSTEM))));
        body.put("contents", contents);
        body.put("tools", List.of(Map.of("functionDeclarations", declarations)));
        Map<String, Object> res = rest.post()
                .uri("/v1beta/models/{model}:generateContent", props.model())
                .header("x-goog-api-key", props.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, resp) -> {
                    int code = resp.getStatusCode().value();
                    log.warn("judge gemini status={}", code);
                    throw new Failure(code == 429 ? "제미나이 한도" : code == 401 || code == 403 ? "제미나이 키 오류" : "제미나이 응답 오류");
                })
                .body(Map.class);
        List<Object> candidates = res == null ? List.of() : (List<Object>) res.getOrDefault("candidates", List.of());
        if (candidates.isEmpty()) {
            throw new Failure("제미나이 빈 응답");
        }
        Map<String, Object> content = asMap(asMap(candidates.get(0)).get("content"));
        if (content.isEmpty()) {
            throw new Failure("제미나이 빈 응답");
        }
        Map<String, Object> out = new LinkedHashMap<>(content);
        out.put("role", "model");
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> calls(Map<String, Object> content) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object p : (List<Object>) content.getOrDefault("parts", List.of())) {
            Object fc = asMap(p).get("functionCall");
            if (fc != null) {
                out.add(asMap(fc));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static String text(Map<String, Object> content) {
        return ((List<Object>) content.getOrDefault("parts", List.of())).stream()
                .map(p -> asMap(p).get("text")).filter(t -> t instanceof String s && !s.isBlank())
                .map(String::valueOf).collect(Collectors.joining(" ")).strip();
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
