package kr.ac.chosun.mspace.climate.application;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import kr.ac.chosun.mspace.climate.domain.Finding;
import kr.ac.chosun.mspace.climate.domain.Metric;

/**
 * 오디 판단 하나 (재웅 11/12 "정리 확인 AI 판단처럼 로딩 돌다가 완료되면 체크").
 * 한 방·한 지표·한 방향(높음/낮음/센서 이상)에 대해 탐지 → 예측 → 알림 → 제어 → 다시 확인 다섯 단계를 남긴다.
 * 다시 확인(VERIFY)은 값이 이 방 평소 범위로 돌아올 때까지 RUNNING으로 남는다(화면의 진짜 로딩).
 * 바뀔 때마다 새 값으로 갈아 끼운다(불변).
 */
public record Decision(String id, int roomId, String roomName, Metric metric, String group, Finding.Kind kind,
                       Instant openedAt, Instant closedAt, Map<Step, StepState> steps) {

    public enum Step { DETECT, PREDICT, NOTIFY, CONTROL, VERIFY }

    public enum Status { RUNNING, DONE, SKIPPED, FAILED }

    public record StepState(Status status, String text, Instant at) {
    }

    public static String key(int roomId, Metric metric, String group) {
        return roomId + ":" + metric + ":" + group;
    }

    public String key() {
        return key(roomId, metric, group);
    }

    public boolean open() {
        return closedAt == null;
    }

    public StepState step(Step s) {
        return steps.get(s);
    }

    public Decision with(Step s, Status status, String text, Instant at) {
        Map<Step, StepState> next = new EnumMap<>(Step.class);
        next.putAll(steps);
        next.put(s, new StepState(status, text, at));
        return new Decision(id, roomId, roomName, metric, group, kind, openedAt, closedAt, next);
    }

    public Decision kind(Finding.Kind k) {
        return new Decision(id, roomId, roomName, metric, group, k, openedAt, closedAt, steps);
    }

    public Decision closed(Instant at) {
        return new Decision(id, roomId, roomName, metric, group, kind, openedAt, at, steps);
    }
}
