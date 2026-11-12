package kr.ac.chosun.mspace.climate.adapter.in.web;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** 응답 시각은 ISO-8601 +09:00 (팀 컨벤션) */
final class Times {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private Times() {
    }

    static String kst(Instant at) {
        return at == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(at.atOffset(KST));
    }
}
