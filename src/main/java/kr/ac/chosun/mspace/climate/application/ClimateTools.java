package kr.ac.chosun.mspace.climate.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import kr.ac.chosun.mspace.climate.appliance.ApplianceKind;
import kr.ac.chosun.mspace.climate.appliance.ApplianceState;
import kr.ac.chosun.mspace.climate.appliance.ClimateFacade;
import kr.ac.chosun.mspace.climate.domain.Finding;
import kr.ac.chosun.mspace.climate.domain.Sample;

/**
 * 오디 공기 도구 (재웅 11/12 "climate가 MCP 서버, 판단 에이전트는 MCP로 도구를 골라 씀").
 * MCP로 열려서 climate의 판단 에이전트(제미나이)와 비서가 같이 쓴다. 숫자 계산(기준선·예측)은 여기서 하고 모델에는 결과만 준다.
 *
 * <p>안전망(규칙)이 도구 안에 있다: 모르는 방·자동 제어를 꺼 둔 방·센서가 끊긴 방·빈 방(재실 모름 포함)에서는 켜지 않고,
 * 목표 온도는 설정 범위(comfort) 밖이면 거절한다. 끄기는 언제나 된다(안전한 쪽).
 * 모델이 무엇을 불러도 이 검사를 건너뛸 수 없다.
 */
public class ClimateTools {

    /** 도구 결과 한 줄. ok=false면 왜 거절했는지(text) */
    public record Result(boolean ok, String text) {
    }

    private final ClimateStore store;
    private final RoomAnalyzer analyzer;
    private final ClimateFacade facade;
    private final ClimateProperties props;
    private final IntFunction<Integer> people;

    /** @param people 방 번호 → 지금 인원(시뮬레이션 방만, 모르면 null) */
    public ClimateTools(ClimateStore store, RoomAnalyzer analyzer, ClimateFacade facade, ClimateProperties props,
                        IntFunction<Integer> people) {
        this.store = store;
        this.analyzer = analyzer;
        this.facade = facade;
        this.props = props;
        this.people = people == null ? id -> null : people;
    }

    /** 방 상태 한 장: 재실·인원, 지표마다 지금 값·이 방 평소 범위·30분 뒤 예측·이상, 기기 상태, 냉난방 목표 범위 */
    public Map<String, Object> roomStatus(int roomId) {
        requireRoom(roomId);
        RoomAnalysis a = analyzer.analyze(store.view(roomId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roomId", roomId);
        out.put("roomName", a.name());
        out.put("occupied", a.occupied() == null ? "모름" : a.occupied() ? "있음" : "없음");
        out.put("people", people.apply(roomId));
        out.put("autoControl", a.auto());
        out.put("sensorOffline", a.offline());
        Map<String, Object> metrics = new LinkedHashMap<>();
        a.metrics().forEach((m, v) -> {
            Map<String, Object> mv = new LinkedHashMap<>();
            mv.put("unit", m.unit());
            mv.put("now", v.latest() == null ? null : round(v.latest().value()));
            mv.put("usualMean", v.baseline().mean());
            mv.put("usualUpper", v.baseline().upper());
            mv.put("usualLower", v.baseline().lower());
            mv.put("changePerMinute", v.forecast().slopePerMinute());
            List<Sample> f = v.forecast().points();
            mv.put("in30min", f.isEmpty() ? null : round(f.get(f.size() - 1).value()));
            mv.put("minutesToLimit", v.forecast().etaMinutes());
            mv.put("findings", v.findings().stream().map(Finding::message).toList());
            metrics.put(m.name(), mv);
        });
        out.put("metrics", metrics);
        List<Map<String, Object>> devices = new ArrayList<>();
        for (ApplianceState s : facade.states(roomId)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("kind", s.kind().name());
            d.put("power", s.power() == null ? "OFF" : s.power().name());
            d.put("mode", s.mode() == null ? null : s.mode().name());
            d.put("targetC", s.targetC());
            d.put("changedBy", s.by());
            devices.add(d);
        }
        out.put("devices", devices);
        out.put("coolTargetRangeC", List.of(props.comfort().coolMin(), props.comfort().coolMax()));
        out.put("heatTargetRangeC", List.of(props.comfort().heatMin(), props.comfort().heatMax()));
        return out;
    }

    /** 에어컨: COOL·HEAT(targetC 필요), DRY, OFF */
    public Result aircon(int roomId, String mode, Integer targetC, String reason) {
        requireRoom(roomId);
        String m = mode == null ? "" : mode.trim().toUpperCase();
        if (m.equals("OFF")) {
            facade.airconOff(roomId, why(reason, "에어컨 끔"));
            return new Result(true, "에어컨 끔");
        }
        String refuse = refuseOn(roomId);
        if (refuse != null) {
            return new Result(false, refuse);
        }
        if (!facade.has(roomId, ApplianceKind.AIRCON)) {
            return new Result(false, "이 방엔 에어컨이 없어요");
        }
        switch (m) {
            case "COOL", "HEAT" -> {
                boolean cool = m.equals("COOL");
                int min = cool ? props.comfort().coolMin() : props.comfort().heatMin();
                int max = cool ? props.comfort().coolMax() : props.comfort().heatMax();
                if (targetC == null || targetC < min || targetC > max) {
                    return new Result(false, "%s 목표 온도는 %d~%d°C 안이어야 해요".formatted(cool ? "냉방" : "난방", min, max));
                }
                String text = (cool ? "에어컨 냉방 " : "에어컨 난방 ") + targetC + "°C";
                if (cool) {
                    facade.cool(roomId, targetC, why(reason, text));
                } else {
                    facade.heat(roomId, targetC, why(reason, text));
                }
                return new Result(true, text);
            }
            case "DRY" -> {
                facade.dry(roomId, why(reason, "에어컨 제습"));
                return new Result(true, "에어컨 제습");
            }
            default -> {
                return new Result(false, "mode는 COOL·HEAT·DRY·OFF 중 하나예요");
            }
        }
    }

    /** 환풍기·공기청정기 켜기·끄기 */
    public Result power(int roomId, ApplianceKind kind, boolean on, String reason) {
        requireRoom(roomId);
        String name = kind == ApplianceKind.VENTILATOR ? "환풍기" : kind == ApplianceKind.PURIFIER ? "공기청정기" : "에어컨";
        if (!facade.has(roomId, kind)) {
            return new Result(false, "이 방엔 " + name + "가 없어요");
        }
        if (on) {
            String refuse = refuseOn(roomId);
            if (refuse != null) {
                return new Result(false, refuse);
            }
        }
        String text = name + (on ? " 켬" : " 끔");
        facade.set(roomId, kind, on, why(reason, text));
        return new Result(true, text);
    }

    /** 켜면 안 되는 이유. 괜찮으면 null */
    private String refuseOn(int roomId) {
        RoomAnalysis a = analyzer.analyze(store.view(roomId));
        if (!a.auto()) {
            return "자동 제어를 꺼 둔 방이라 켤 수 없어요";
        }
        if (a.offline()) {
            return "센서 값이 끊긴 방이라 켤 수 없어요";
        }
        if (a.occupied() == null) {
            return "사람이 있는지 몰라 켤 수 없어요";
        }
        if (!a.occupied()) {
            return "빈 방이라 켤 수 없어요";
        }
        return null;
    }

    private void requireRoom(int roomId) {
        if (!store.roomIds().contains(roomId)) {
            throw new IllegalArgumentException("없는 방: " + roomId);
        }
    }

    private static String why(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason.strip();
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
