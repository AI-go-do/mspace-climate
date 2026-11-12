package kr.ac.chosun.mspace.climate.appliance;

import java.time.Instant;

/**
 * 기기 지금 상태. 적외선은 한 방향이라 "보낸 명령" 기준이다(실제 켜졌는지는 다음 온도로 본다).
 * by: AUTO(오디) / ADMIN / STUDENT. 아직 아무도 안 건드렸으면 null
 */
public record ApplianceState(String applianceId, int roomId, ApplianceKind kind, String brand, String adapter,
                             ApplianceCommand.Power power, ApplianceCommand.Mode mode, Integer targetC,
                             Instant changedAt, String by) {
}
