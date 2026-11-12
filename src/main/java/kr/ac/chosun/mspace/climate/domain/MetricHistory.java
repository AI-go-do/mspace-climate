package kr.ac.chosun.mspace.climate.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 한 방·한 지표의 1분 값 기록. 같은 분에 두 번 오면 평균. 보관 기간(기본 7일)이 지난 값은 버린다.
 * 방 5개 × 지표 3개 × 7일 = 15만 칸 안팎이라 메모리에 둔다(스냅숏 파일로 재시작해도 이어짐).
 * 스레드 안전하지 않다. 바깥(ClimateStore)이 방마다 잠근다.
 */
public final class MetricHistory {

    private final Duration keep;
    private final Deque<Minute> minutes = new ArrayDeque<>();

    public MetricHistory(Duration keep) {
        this.keep = keep;
    }

    /** 값을 더한다. 마지막 분보다 오래된 값(늦게 온 것)은 버린다. 들어갔으면 true */
    public boolean add(Instant at, double value) {
        long minute = at.truncatedTo(ChronoUnit.MINUTES).getEpochSecond() / 60;
        Minute last = minutes.peekLast();
        if (last != null && minute < last.minute) {
            return false;
        }
        if (last != null && minute == last.minute) {
            last.sum += value;
            last.count++;
        } else {
            minutes.addLast(new Minute(minute, value, 1));
        }
        long oldest = minute - keep.toMinutes();
        while (!minutes.isEmpty() && minutes.peekFirst().minute < oldest) {
            minutes.removeFirst();
        }
        return true;
    }

    public List<Sample> all() {
        List<Sample> out = new ArrayList<>(minutes.size());
        for (Minute m : minutes) {
            out.add(m.sample());
        }
        return out;
    }

    /** since 이후(포함) 값 */
    public List<Sample> since(Instant since) {
        long from = since.getEpochSecond() / 60;
        List<Sample> out = new ArrayList<>();
        var it = minutes.descendingIterator();
        while (it.hasNext()) {
            Minute m = it.next();
            if (m.minute < from) {
                break;
            }
            out.add(0, m.sample());
        }
        return out;
    }

    public Sample latest() {
        Minute m = minutes.peekLast();
        return m == null ? null : m.sample();
    }

    public int size() {
        return minutes.size();
    }

    private static final class Minute {
        final long minute;
        double sum;
        int count;

        Minute(long minute, double sum, int count) {
            this.minute = minute;
            this.sum = sum;
            this.count = count;
        }

        Sample sample() {
            return new Sample(Instant.ofEpochSecond(minute * 60), sum / count);
        }
    }
}
