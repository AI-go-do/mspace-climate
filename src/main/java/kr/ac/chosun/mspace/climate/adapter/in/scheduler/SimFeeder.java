package kr.ac.chosun.mspace.climate.adapter.in.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.climate.appliance.ApplianceCommand;
import kr.ac.chosun.mspace.climate.appliance.ApplianceKind;
import kr.ac.chosun.mspace.climate.appliance.ApplianceState;
import kr.ac.chosun.mspace.climate.appliance.ClimateFacade;
import kr.ac.chosun.mspace.climate.application.ReadingIntake;
import kr.ac.chosun.mspace.climate.application.RoomSimulator;
import kr.ac.chosun.mspace.climate.application.SensorReading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 시뮬레이션 값을 1초마다 넣는다(climate.sim.enabled=true일 때만). 오디가 켠 기기 상태를 읽어 시뮬레이션에 돌려준다
 * (에어컨을 켜면 온도가 실제로 내려감). 판단은 judgeEvery초마다. 켜질 때는 지난 backfill분을 조용히 채워 기준선이 바로 잡히게 한다.
 */
public class SimFeeder {

    private static final Logger log = LoggerFactory.getLogger(SimFeeder.class);

    private final RoomSimulator sim;
    private final ReadingIntake intake;
    private final ClimateFacade facade;
    private final List<Integer> rooms;
    private final int backfillMinutes;
    private final int judgeEvery;
    private final Clock clock;
    private final Map<Integer, Boolean> lastOccupied = new HashMap<>();
    private Instant last;

    public SimFeeder(RoomSimulator sim, ReadingIntake intake, ClimateFacade facade, List<Integer> rooms,
                     int backfillMinutes, int judgeEvery, Clock clock) {
        this.sim = sim;
        this.intake = intake;
        this.facade = facade;
        this.rooms = rooms;
        this.backfillMinutes = backfillMinutes;
        this.judgeEvery = Math.max(1, judgeEvery);
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        Instant now = clock.instant();
        Instant t = now.minusSeconds(60L * backfillMinutes);
        sim.start(t);
        int n = 0;
        for (int m = backfillMinutes; m >= 1; m--) {
            t = now.minusSeconds(60L * m);
            sim.step(t, 1.0, Map.of());
            n += feed(t, false, true);
        }
        last = now;
        log.info("시뮬레이션 시작: 지난 {}분 채움 {}건", backfillMinutes, n);
    }

    @Scheduled(fixedRateString = "${climate.sim.every:1000}", initialDelayString = "${climate.sim.every:1000}")
    public synchronized void tick() {
        Instant now = clock.instant();
        if (last == null) {
            sim.start(now);
            last = now;
        }
        double dt = Math.min(5.0, Math.max(0, (now.toEpochMilli() - last.toEpochMilli()) / 60_000.0));
        last = now;
        sim.step(now, dt, devices());
        feed(now, now.getEpochSecond() % judgeEvery == 0, false);
    }

    private int feed(Instant at, boolean judge, boolean quiet) {
        int n = 0;
        long sec = at.getEpochSecond();
        for (RoomSimulator.State s : sim.read()) {
            Boolean before = lastOccupied.put(s.roomId(), s.occupied());
            if (judge || quiet || before == null || before != s.occupied()) {
                intake.simulated(new SensorReading("sim-occ-" + s.roomId() + "-" + sec, s.roomId(), "OCCUPANCY",
                        s.occupied(), null, null, null, at), false, quiet);
            }
            boolean changed = before != null && before != s.occupied();
            n += intake.simulated(new SensorReading("sim-" + s.roomId() + "-" + sec, s.roomId(), "CO2", null,
                    (int) Math.round(s.co2()), round(s.temperatureC(), 2), round(s.humidityPct(), 1), at),
                    judge || changed, quiet) ? 1 : 0;
        }
        return n;
    }

    /** 방마다 지금 켜진 기기 (가상·실제 상관없이 파사드가 아는 상태) */
    private Map<Integer, RoomSimulator.Devices> devices() {
        Map<Integer, RoomSimulator.Devices> out = new HashMap<>();
        for (int room : rooms) {
            boolean cool = false;
            boolean heat = false;
            boolean dry = false;
            boolean vent = false;
            Integer target = null;
            for (ApplianceState a : facade.states(room)) {
                if (a == null || a.power() != ApplianceCommand.Power.ON) {
                    continue;
                }
                if (a.kind() == ApplianceKind.AIRCON) {
                    cool |= a.mode() == ApplianceCommand.Mode.COOL;
                    heat |= a.mode() == ApplianceCommand.Mode.HEAT;
                    dry |= a.mode() == ApplianceCommand.Mode.DRY;
                    target = a.targetC();
                } else if (a.kind() == ApplianceKind.VENTILATOR) {
                    vent = true;
                }
            }
            out.put(room, new RoomSimulator.Devices(cool, heat, dry, target, vent));
        }
        return out;
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
