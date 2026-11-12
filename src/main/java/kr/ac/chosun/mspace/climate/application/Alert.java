package kr.ac.chosun.mspace.climate.application;

import java.time.Instant;
import kr.ac.chosun.mspace.climate.domain.Finding;
import kr.ac.chosun.mspace.climate.domain.Metric;

/**
 * 알림 하나. audience ROOM = 그 방을 쓰는 학생(환기·곧 더워짐), ADMIN = 관리자 화면(센서 이상). 관리자는 보기만 한다(무인 운영).
 */
public record Alert(Instant at, int roomId, Audience audience, Metric metric, Finding.Kind kind, String message,
                    Integer etaMinutes) {

    public enum Audience { ROOM, ADMIN }
}
