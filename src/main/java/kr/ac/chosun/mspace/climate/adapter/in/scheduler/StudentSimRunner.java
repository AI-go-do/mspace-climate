package kr.ac.chosun.mspace.climate.adapter.in.scheduler;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import kr.ac.chosun.mspace.climate.application.StudentSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * 가상 학생을 10초마다 움직인다. core를 부르는 일이라 공기 시뮬레이션(1초)·판단을 막지 않게 따로 스레드에서 돈다
 */
public class StudentSimRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StudentSimRunner.class);

    private final StudentSimulator students;
    private final Clock clock;
    private final long everySeconds;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "climate-students");
        t.setDaemon(true);
        return t;
    });

    public StudentSimRunner(StudentSimulator students, Clock clock, long everySeconds) {
        this.students = students;
        this.clock = clock;
        this.everySeconds = Math.max(1, everySeconds);
    }

    /** 공기 시뮬레이션이 지난 시간을 채운 뒤에 시작한다 */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        worker.scheduleWithFixedDelay(this::tick, 5, everySeconds, TimeUnit.SECONDS);
    }

    void tick() {
        try {
            students.tick(clock.instant());
        } catch (RuntimeException e) {
            log.warn("가상 학생 한 바퀴 실패 ({})", e.getClass().getSimpleName());
        }
    }

    public StudentSimulator students() {
        return students;
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
