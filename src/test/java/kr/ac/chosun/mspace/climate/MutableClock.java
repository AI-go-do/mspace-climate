package kr.ac.chosun.mspace.climate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** 테스트용 시계: 원하는 만큼 앞으로 돌린다 */
public class MutableClock extends Clock {

    private Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public void advance(Duration d) {
        now = now.plus(d);
    }

    public void set(Instant at) {
        now = at;
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("Asia/Seoul");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
