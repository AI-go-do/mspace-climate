package kr.ac.chosun.mspace.climate.adapter.in.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.climate.application.LabReplay;
import kr.ac.chosun.mspace.climate.application.ReadingIntake;
import kr.ac.chosun.mspace.climate.application.SensorReading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 실습실 센서가 멈췄을 때 1분마다 실측 녹화를 지금 시각에 맞춰 넣는다(climate.replay.enabled=true일 때만).
 * 켜질 때는 지난 backfill분을 조용히 채워 그래프가 비지 않게 한다. 실제 값이 오는 방은 ReadingIntake가 재생 값을 버린다.
 */
public class ReplayFeeder {

    private static final Logger log = LoggerFactory.getLogger(ReplayFeeder.class);

    private final LabReplay replay;
    private final ReadingIntake intake;
    private final Map<String, Integer> spots;
    private final int backfillMinutes;
    private final Clock clock;

    public ReplayFeeder(LabReplay replay, ReadingIntake intake, Map<String, Integer> spots, int backfillMinutes, Clock clock) {
        this.replay = replay;
        this.intake = intake;
        this.spots = spots;
        this.backfillMinutes = backfillMinutes;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        Instant now = clock.instant();
        int n = 0;
        for (int m = backfillMinutes; m >= 0; m--) { // 지금 분까지 채운다(다음 1분 넣기는 다음 분부터)
            for (SensorReading r : ordered(replay.at(now.minusSeconds(60L * m), spots))) {
                n += intake.replay(r, true) ? 1 : 0;
            }
        }
        log.info("녹화 재생 시작: 지난 {}분 채움 {}건", backfillMinutes, n);
    }

    @Scheduled(cron = "${climate.replay.cron:5 * * * * *}")
    public void tick() {
        for (SensorReading r : ordered(replay.at(clock.instant(), spots))) {
            intake.replay(r, false);
        }
    }

    /** 실제 센서 방(만든 값의 원본)을 마지막에: 다른 방이 먼저 재생 방으로 잡혀야 그 방에 만든 값이 섞이지 않는다 */
    private static List<SensorReading> ordered(List<SensorReading> in) {
        return in.stream().sorted(Comparator.comparingInt(SensorReading::roomId).reversed()).toList();
    }
}
