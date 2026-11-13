package kr.ac.chosun.mspace.climate.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.climate.appliance.ApplianceKind;
import kr.ac.chosun.mspace.climate.domain.Metric;
import kr.ac.chosun.mspace.climate.domain.MetricRule;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * climate 설정값 (TEAM.md 4장: 숫자는 코드에 박지 않는다). 기본값은 application.yml.
 *
 * @param rooms         방 번호 → 이름
 * @param keep          기준선을 잡을 기록 기간 (7일)
 * @param k             경계 = 평균 ± k × 흩어짐
 * @param minSamples    기준선을 믿을 최소 1분 값 개수
 * @param trendWindow   추세를 잴 최근 기간 (30분)
 * @param minTrendPoints 추세를 잴 최소 값 개수
 * @param lead          이 안에 경계에 닿을 것 같으면 미리 알림·제어 (10분)
 * @param stuck         값이 이만큼 그대로면 센서 멈춤 후보
 * @param offline       값이 이만큼 안 오면 끊김
 * @param emptyOff      빈 방이 이만큼 이어지면 기기를 끈다
 * @param holdManual    사람이 손으로 바꾼 기기는 이만큼 오디가 안 건드린다
 * @param alertEvery    같은 방·같은 알림은 이 간격 안에 다시 안 보낸다 (FR-DEV-104: 30분)
 * @param rules         지표별 안전 한계
 * @param comfort       냉난방 목표 온도 범위
 * @param appliances    방마다 기기. 비면 방마다 가상 에어컨·공기청정기를 둔다
 * @param derive        실제 센서가 없는 방의 값을 실제 방 값에서 만드는 설정 (null이면 안 만듦)
 * @param settle        이상이 이만큼 계속 사라져야 판단 카드를 "돌아왔어요"로 닫는다. 경계 언저리에서 1~2분마다
 *                      카드가 열리고 닫히는 것을 막는다(dev 11/12 3번 방 온도). null이면 바로 닫음
 */
@ConfigurationProperties("climate")
public record ClimateProperties(Map<Integer, String> rooms, Duration keep, double k, int minSamples,
                                Duration trendWindow, int minTrendPoints, Duration lead, Duration stuck,
                                Duration offline, Duration emptyOff, Duration holdManual, Duration alertEvery,
                                Map<Metric, MetricRule> rules, Comfort comfort, List<ApplianceConfig> appliances,
                                String dataDir, RoomDeriver derive, Duration settle) {

    public Duration settleOrZero() {
        return settle == null ? Duration.ZERO : settle;
    }

    public record Comfort(int coolMin, int coolMax, int heatMin, int heatMax) {
    }

    /**
     * 기기 한 대. adapter: virtual / ir(에어컨, url = ESP32 적외선 노드) / tasmota(전원 기기, url = 플러그)
     */
    public record ApplianceConfig(String id, int room, ApplianceKind kind, String adapter, String brand, String url) {
    }
}
