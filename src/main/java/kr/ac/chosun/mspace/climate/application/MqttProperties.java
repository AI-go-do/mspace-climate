package kr.ac.chosun.mspace.climate.application;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 교육장 MQTT 브로커 직접 구독 (재웅 11/12). 끄면(enabled=false) 게이트웨이가 넘겨 주는 사본만 쓴다.
 *
 * @param enabled        켜기
 * @param uri            브로커 (tcp://host:1883)
 * @param username       계정 (.env, 저장소에 넣지 않음)
 * @param password       비밀번호 (.env)
 * @param clientId       브로커에서 겹치면 서로 끊으므로 수집기와 다른 이름
 * @param rawTopic       센서 원시 값 토픽 (ChirpStack uplink)
 * @param location       이 공간(태그 location) 센서만 받는다
 * @param spots          센서 자리(태그 spot) → 방 번호
 * @param autoRooms      설정에 없는 CO2 센서를 받은 순서대로 붙일 방 (비면 자동으로 안 붙임)
 * @param inferenceTopic 보드 A 시계열 모델 결과 토픽 (retained)
 */
@ConfigurationProperties("climate.mqtt")
public record MqttProperties(boolean enabled, String uri, String username, String password, String clientId,
                             String rawTopic, String location, Map<String, Integer> spots, java.util.List<Integer> autoRooms,
                             String inferenceTopic) {
}
