package kr.ac.chosun.mspace.climate.application;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 오디 판단 기록. 열린 판단(다시 확인 중)과 최근 판단 keep개를 들고, 바뀔 때마다 듣는 쪽(화면 밀어 주기)에 알린다.
 */
public class DecisionBook {

    private final Map<String, Decision> open = new HashMap<>();
    private final Deque<Decision> recent = new ArrayDeque<>();
    private final int keep;
    private volatile Consumer<Decision> listener = d -> { };

    public DecisionBook(int keep) {
        this.keep = keep;
    }

    public void listen(Consumer<Decision> l) {
        this.listener = Objects.requireNonNull(l);
    }

    public synchronized Decision find(String key) {
        return open.get(key);
    }

    public synchronized List<Decision> openIn(int roomId) {
        return open.values().stream().filter(d -> d.roomId() == roomId).toList();
    }

    /** 새 판단이나 바뀐 판단을 넣는다. 닫힌 판단은 열린 목록에서 빠진다 */
    public void put(Decision d) {
        synchronized (this) {
            if (d.open()) {
                open.put(d.key(), d);
            } else {
                open.remove(d.key());
            }
            recent.removeIf(x -> x.id().equals(d.id()));
            recent.addFirst(d);
            while (recent.size() > keep) {
                Decision gone = recent.removeLast();
                if (gone.open()) {
                    open.remove(gone.key());
                }
            }
        }
        listener.accept(d);
    }

    public synchronized List<Decision> recent(int limit) {
        return recent.stream().limit(limit).toList();
    }

    public synchronized void restore(List<Decision> list) {
        open.clear();
        recent.clear();
        if (list == null) {
            return;
        }
        list.stream().limit(keep).forEach(d -> {
            recent.addLast(d);
            if (d.open()) {
                open.putIfAbsent(d.key(), d);
            }
        });
    }
}
