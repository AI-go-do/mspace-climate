package kr.ac.chosun.mspace.climate.application;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.climate.domain.Finding;

/**
 * 알림 기록 + 같은 알림 거르기 (FR-DEV-104: 같은 방에 30분에 한 번). 곧 넘음(RISING_SOON)과 넘음(OVER)은 같은 묶음이라
 * 미리 알림을 보낸 뒤 실제로 넘어도 30분 안에는 다시 안 보낸다.
 */
public class AlertLog {

    private final Deque<Alert> alerts = new ArrayDeque<>();
    private final Map<String, java.time.Instant> lastSent = new HashMap<>();
    private final Duration every;
    private final int keep;
    private final Notifier notifier;
    private final Clock clock;

    public AlertLog(Duration every, int keep, Notifier notifier, Clock clock) {
        this.every = every;
        this.keep = keep;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** 보냈으면 true, 30분 안에 같은 것을 보냈으면 false */
    public synchronized boolean raise(Alert a) {
        String key = a.roomId() + ":" + a.audience() + ":" + a.metric() + ":" + group(a.kind());
        var last = lastSent.get(key);
        if (last != null && last.plus(every).isAfter(clock.instant())) {
            return false;
        }
        lastSent.put(key, clock.instant());
        alerts.addFirst(a);
        while (alerts.size() > keep) {
            alerts.removeLast();
        }
        notifier.send(a);
        return true;
    }

    public synchronized List<Alert> recent(int limit) {
        return alerts.stream().limit(limit).toList();
    }

    public synchronized void restore(List<Alert> list) {
        alerts.clear();
        list.stream().limit(keep).forEach(alerts::addLast);
    }

    private static String group(Finding.Kind k) {
        return switch (k) {
            case OVER, RISING_SOON -> "HIGH";
            case UNDER, FALLING_SOON -> "LOW";
            default -> k.name();
        };
    }
}
