package kr.ac.chosun.mspace.climate.appliance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 기기 파사드 (FR-DEV-106). 오디·화면은 "N번 방 냉방 25도", "N번 방 공기청정기 켜" 같은 말만 하고,
 * 어느 어댑터(가상·적외선 삼성/LG·플러그)인지는 몰라도 된다. 기종이 늘어도 여기와 부르는 쪽에 if가 늘지 않는다.
 * 모든 명령은 조치 기록(누가·언제·왜)에 남는다. 사람이 손으로 바꾼 기기는 holdManual 동안 오디가 건드리지 않는다.
 */
public class ClimateFacade {

    public static final String BY_AUTO = "AUTO";

    private final Map<String, Appliance> appliances = new LinkedHashMap<>();
    private final Map<String, ApplianceState> states = new ConcurrentHashMap<>();
    private final Deque<Action> actions = new ArrayDeque<>();
    private final int keepActions;
    private final Duration holdManual;
    private final Clock clock;

    public ClimateFacade(Collection<Appliance> list, Duration holdManual, int keepActions, Clock clock) {
        for (Appliance a : list) {
            if (appliances.putIfAbsent(a.id(), a) != null) {
                throw new IllegalArgumentException("기기 id가 겹쳐요: " + a.id());
            }
            states.put(a.id(), new ApplianceState(a.id(), a.roomId(), a.kind(), a.brand(), a.adapter(),
                    ApplianceCommand.Power.OFF, null, null, null, null));
        }
        this.holdManual = holdManual;
        this.keepActions = keepActions;
        this.clock = clock;
    }

    // ---------- 오디(자동)가 쓰는 말 ----------

    /** 냉방 targetC도로 켠다. 이미 그 상태면 아무것도 안 하고 false */
    public boolean cool(int roomId, int targetC, String reason) {
        return toAll(roomId, ApplianceKind.AIRCON, ApplianceCommand.aircon(ApplianceCommand.Mode.COOL, targetC), reason);
    }

    public boolean heat(int roomId, int targetC, String reason) {
        return toAll(roomId, ApplianceKind.AIRCON, ApplianceCommand.aircon(ApplianceCommand.Mode.HEAT, targetC), reason);
    }

    public boolean dry(int roomId, String reason) {
        return toAll(roomId, ApplianceKind.AIRCON, ApplianceCommand.aircon(ApplianceCommand.Mode.DRY, 26), reason);
    }

    public boolean airconOff(int roomId, String reason) {
        return toAll(roomId, ApplianceKind.AIRCON, ApplianceCommand.off(), reason);
    }

    public boolean set(int roomId, ApplianceKind kind, boolean on, String reason) {
        return toAll(roomId, kind, on ? ApplianceCommand.on() : ApplianceCommand.off(), reason);
    }

    /** 방의 켜진 기기를 모두 끈다 (빈 방) */
    public boolean allOff(int roomId, String reason) {
        boolean any = false;
        for (ApplianceKind k : ApplianceKind.values()) {
            any |= toAll(roomId, k, ApplianceCommand.off(), reason);
        }
        return any;
    }

    // ---------- 사람(관리자·학생)이 쓰는 말 ----------

    /** 기기 하나에 바로 명령. 사람이 바꾼 기기는 holdManual 동안 오디가 건드리지 않는다 */
    public ApplianceState command(String applianceId, ApplianceCommand c, String by, String reason) {
        Appliance a = appliances.get(applianceId);
        if (a == null) {
            throw new IllegalArgumentException("없는 기기: " + applianceId);
        }
        return apply(a, normalize(a, c), by, reason);
    }

    // ---------- 보기 ----------

    public List<ApplianceState> states(int roomId) {
        return appliances.values().stream().filter(a -> a.roomId() == roomId).map(a -> states.get(a.id())).toList();
    }

    public Optional<Appliance> find(String applianceId) {
        return Optional.ofNullable(appliances.get(applianceId));
    }

    public boolean has(int roomId, ApplianceKind kind) {
        return appliances.values().stream().anyMatch(a -> a.roomId() == roomId && a.kind() == kind);
    }

    public synchronized List<Action> recentActions(int limit) {
        return actions.stream().limit(limit).toList();
    }

    /** 스냅숏에서 되살리기 */
    public void restore(ApplianceState s) {
        if (appliances.containsKey(s.applianceId())) {
            states.put(s.applianceId(), s);
        }
    }

    public synchronized void restoreActions(List<Action> list) {
        actions.clear();
        list.stream().limit(keepActions).forEach(actions::addLast);
    }

    // ---------- 안쪽 ----------

    private boolean toAll(int roomId, ApplianceKind kind, ApplianceCommand c, String reason) {
        boolean changed = false;
        for (Appliance a : appliances.values()) {
            if (a.roomId() != roomId || a.kind() != kind) {
                continue;
            }
            ApplianceState now = states.get(a.id());
            if (same(now, c) || heldByPerson(now)) {
                continue;
            }
            try {
                apply(a, normalize(a, c), BY_AUTO, reason);
                changed = true;
            } catch (ApplianceException e) {
                // 실패도 조치 기록에 남았다. 다음 판단(1분 뒤)에 다시 시도한다
            }
        }
        return changed;
    }

    private boolean heldByPerson(ApplianceState s) {
        return s.by() != null && !BY_AUTO.equals(s.by()) && s.changedAt() != null
                && s.changedAt().plus(holdManual).isAfter(clock.instant());
    }

    private static boolean same(ApplianceState s, ApplianceCommand c) {
        if (s.power() != c.power()) {
            return false;
        }
        return c.power() == ApplianceCommand.Power.OFF
                || (s.mode() == c.mode() && java.util.Objects.equals(s.targetC(), c.targetC()));
    }

    private static ApplianceCommand normalize(Appliance a, ApplianceCommand c) {
        if (a.kind() != ApplianceKind.AIRCON || c.power() == ApplianceCommand.Power.OFF) {
            return new ApplianceCommand(c.power(), null, null);
        }
        return new ApplianceCommand(c.power(), c.mode() == null ? ApplianceCommand.Mode.COOL : c.mode(),
                c.targetC() == null ? 26 : c.targetC());
    }

    private ApplianceState apply(Appliance a, ApplianceCommand c, String by, String reason) {
        Instant now = clock.instant();
        String error = null;
        try {
            a.send(c);
        } catch (ApplianceException e) {
            error = e.getMessage();
        }
        ApplianceState before = states.get(a.id());
        ApplianceState after = error == null
                ? new ApplianceState(a.id(), a.roomId(), a.kind(), a.brand(), a.adapter(), c.power(), c.mode(),
                c.targetC(), now, by)
                : before;
        states.put(a.id(), after);
        record(new Action(now, a.roomId(), a.id(), a.kind(), c, by, reason, error == null, error));
        if (error != null) {
            throw new ApplianceException(error);
        }
        return after;
    }

    private synchronized void record(Action action) {
        actions.addFirst(action);
        while (actions.size() > keepActions) {
            actions.removeLast();
        }
    }

    /** 조치 기록 한 줄 (FR-DEV-106: 언제·어느 방에·무엇을 했는지) */
    public record Action(Instant at, int roomId, String applianceId, ApplianceKind kind, ApplianceCommand command,
                         String by, String reason, boolean ok, String error) {
    }
}
