package kr.ac.chosun.mspace.climate.adapter.in.web;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 실시간으로 밀어 주기 (재웅 11/12 "새 값이 실시간으로 바뀌어야"). 대시보드가 열어 둔 연결(SSE)에
 * 센서 값이 들어오는 그 순간 보내서, 화면이 물어보러 오지 않아도 바로 바뀐다.
 * <ul>
 *   <li>reading: 방 하나의 새 값 {roomId, at, co2, temperatureC, humidityPct, source}</li>
 *   <li>tick: 오디가 한 바퀴 판단을 마침 → 화면이 /api/climate/rooms·log를 다시 읽음</li>
 *   <li>20초마다 빈 줄(ping): 학교 nginx·게이트웨이가 조용한 연결을 끊지 않게</li>
 * </ul>
 * 연결은 최대 200개. 30분이면 끝내고 화면이 다시 붙는다.
 */
@Component
public class LiveFeed {

    static final int MAX = 200;
    static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter open() {
        if (emitters.size() >= MAX) {
            SseEmitter oldest = emitters.remove(0);
            oldest.complete();
        }
        SseEmitter e = new SseEmitter(TIMEOUT_MS);
        emitters.add(e);
        e.onCompletion(() -> emitters.remove(e));
        e.onTimeout(() -> emitters.remove(e));
        e.onError(x -> emitters.remove(e));
        send(e, SseEmitter.event().name("hello").data("{}"));
        return e;
    }

    public void publish(String name, Object data) {
        for (SseEmitter e : emitters) {
            send(e, SseEmitter.event().name(name).data(data));
        }
    }

    @Scheduled(fixedDelay = 20_000)
    void ping() {
        for (SseEmitter e : emitters) {
            send(e, SseEmitter.event().comment("ping"));
        }
    }

    int size() {
        return emitters.size();
    }

    private void send(SseEmitter e, SseEmitter.SseEventBuilder event) {
        try {
            e.send(event);
        } catch (IOException | IllegalStateException x) {
            emitters.remove(e);
        }
    }
}
