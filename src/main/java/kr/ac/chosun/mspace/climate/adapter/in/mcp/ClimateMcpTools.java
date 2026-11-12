package kr.ac.chosun.mspace.climate.adapter.in.mcp;

import java.util.Map;
import kr.ac.chosun.mspace.climate.appliance.ApplianceKind;
import kr.ac.chosun.mspace.climate.application.ClimateTools;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 오디 공기 도구를 MCP로 연다 (POST /mcp, Streamable HTTP. 비서 mcp-server와 같은 방식).
 * climate의 판단 에이전트(제미나이)가 이 도구로 방을 보고 기기를 고른다. 비서도 같은 도구를 붙일 수 있다.
 * 안전 검사(빈 방·자동 끔·센서 끊김·온도 범위)는 ClimateTools 안에 있어 누가 불러도 건너뛸 수 없다.
 * 게이트웨이는 /api/climate/**만 넘기므로 /mcp는 도커 안쪽에서만 닿는다
 */
@Component
class ClimateMcpTools {

    private final ClimateTools tools;

    ClimateMcpTools(ClimateTools tools) {
        this.tools = tools;
    }

    @McpTool(name = "room_status",
            description = """
                    방 하나의 공기와 기기 상태를 본다. 사람 있음·인원, 지표(CO2·TEMPERATURE·HUMIDITY)마다 지금 값,
                    이 방 평소 범위(usualLower~usualUpper), 1분 변화량, 30분 뒤 예측(in30min), 경계까지 남은 분(minutesToLimit),
                    이상(findings), 기기(AIRCON·VENTILATOR·PURIFIER) 상태, 냉방·난방 목표 온도로 고를 수 있는 범위를 준다.
                    기기를 바꾸기 전에 먼저 부른다.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    Map<String, Object> roomStatus(@McpToolParam(description = "방 번호 (1~5)") int roomId) {
        return tools.roomStatus(roomId);
    }

    @McpTool(name = "set_aircon",
            description = """
                    에어컨을 바꾼다. mode는 COOL(냉방)·HEAT(난방)·DRY(제습)·OFF(끄기). COOL·HEAT는 targetC가 필요하고
                    room_status의 coolTargetRangeC·heatTargetRangeC 안이어야 한다. 빈 방·사람 있는지 모름·자동 제어 끔·센서 끊김이면
                    켜기는 거절된다(끄기는 언제나 된다). 결과 ok=false면 text가 거절 이유.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    ClimateTools.Result setAircon(
            @McpToolParam(description = "방 번호 (1~5)") int roomId,
            @McpToolParam(description = "COOL, HEAT, DRY, OFF 중 하나") String mode,
            @McpToolParam(description = "목표 온도 °C (COOL·HEAT일 때만)", required = false) Integer targetC,
            @McpToolParam(description = "왜 바꾸는지 한 문장 (화면 기록에 남는다)", required = false) String reason) {
        return tools.aircon(roomId, mode, targetC, reason);
    }

    @McpTool(name = "set_ventilator",
            description = """
                    환풍기를 켜거나 끈다. CO2를 낮추는 기기는 환풍기(바깥 공기)뿐이다. 켜기는 빈 방·사람 있는지 모름·자동 제어 끔·
                    센서 끊김이면 거절된다. 결과 ok=false면 text가 거절 이유.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    ClimateTools.Result setVentilator(
            @McpToolParam(description = "방 번호 (1~5)") int roomId,
            @McpToolParam(description = "true면 켬, false면 끔") boolean on,
            @McpToolParam(description = "왜 바꾸는지 한 문장", required = false) String reason) {
        return tools.power(roomId, ApplianceKind.VENTILATOR, on, reason);
    }

    @McpTool(name = "set_purifier",
            description = """
                    공기청정기를 켜거나 끈다. 공기청정기는 먼지만 거르고 CO2는 못 낮춘다(환기로 창문을 열 때 들어오는 먼지용).
                    켜기는 빈 방·사람 있는지 모름·자동 제어 끔·센서 끊김이면 거절된다.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    ClimateTools.Result setPurifier(
            @McpToolParam(description = "방 번호 (1~5)") int roomId,
            @McpToolParam(description = "true면 켬, false면 끔") boolean on,
            @McpToolParam(description = "왜 바꾸는지 한 문장", required = false) String reason) {
        return tools.power(roomId, ApplianceKind.PURIFIER, on, reason);
    }
}
