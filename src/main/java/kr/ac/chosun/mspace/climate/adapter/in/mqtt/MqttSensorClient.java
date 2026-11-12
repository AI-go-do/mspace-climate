package kr.ac.chosun.mspace.climate.adapter.in.mqtt;

import java.time.Clock;
import java.time.Instant;
import kr.ac.chosun.mspace.climate.application.BoardModel;
import kr.ac.chosun.mspace.climate.application.MqttProperties;
import kr.ac.chosun.mspace.climate.application.ReadingIntake;
import kr.ac.chosun.mspace.climate.application.SensorReading;
import kr.ac.chosun.mspace.climate.application.SensorRegistry;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * 교육장 브로커를 직접 구독한다 (재웅 11/12 "직접 구독"). 센서가 값을 올린 그 순간 받는다(젯슨·core를 거치지 않음).
 * - 원시 센서: location이 설정한 공간인 것만. 자리(spot) → 방 (SensorRegistry). 공기 값 하나 = 그 방 이벤트 하나
 * - 보드 A 모델 결과: 공간별 최신 예측·이상 점수 (BoardModel)
 * 끊기면 Paho가 다시 붙고, 다시 붙을 때마다 구독을 다시 건다. 로그에는 방 번호·자리만 남긴다(센서 원문·계정 안 남김).
 */
public class MqttSensorClient implements MqttCallbackExtended, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MqttSensorClient.class);

    private final MqttProperties props;
    private final ReadingIntake intake;
    private final SensorRegistry sensors;
    private final BoardModel model;
    private final Clock clock;
    private MqttClient client;

    public MqttSensorClient(MqttProperties props, ReadingIntake intake, SensorRegistry sensors, BoardModel model,
                            Clock clock) {
        this.props = props;
        this.intake = intake;
        this.sensors = sensors;
        this.model = model;
        this.clock = clock;
    }

    @Override
    public void afterPropertiesSet() throws MqttException {
        if (props.username() == null || props.username().isBlank()) {
            log.info("MQTT 계정이 비어 있어 직접 구독은 끔 (게이트웨이 사본만 씀)");
            return;
        }
        String id = props.clientId() == null || props.clientId().isBlank()
                ? "mspace-climate-" + java.util.UUID.randomUUID().toString().substring(0, 6) : props.clientId();
        client = new MqttClient(props.uri(), id, new MemoryPersistence());
        client.setCallback(this);
        MqttConnectOptions o = new MqttConnectOptions();
        o.setAutomaticReconnect(true);
        o.setCleanSession(true);
        o.setConnectionTimeout(10);
        o.setKeepAliveInterval(30);
        o.setUserName(props.username());
        o.setPassword(props.password() == null ? new char[0] : props.password().toCharArray());
        // 처음 연결이 안 돼도 서비스는 켜고(게이트웨이 사본으로 돎) 뒤에서 다시 붙는다
        Thread.ofVirtual().name("mqtt-connect").start(() -> connect(o));
    }

    private void connect(MqttConnectOptions o) {
        long wait = 2_000;
        while (!client.isConnected()) {
            try {
                client.connect(o);
                return;
            } catch (MqttException e) {
                log.warn("MQTT 연결 실패 (code {}), {}초 뒤 다시", e.getReasonCode(), wait / 1000);
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                wait = Math.min(60_000, wait * 2);
            }
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        try {
            client.subscribe(props.rawTopic(), 0);
            if (props.inferenceTopic() != null && !props.inferenceTopic().isBlank()) {
                client.subscribe(props.inferenceTopic(), 1);
            }
            log.info("MQTT {} 구독 시작", reconnect ? "다시" : "");
        } catch (MqttException e) {
            log.warn("MQTT 구독 실패 (code {})", e.getReasonCode());
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        Instant now = clock.instant();
        try {
            if (topic.endsWith("/state") && topic.contains("/inference/")) {
                var s = MqttMessages.inference(message.getPayload(), now);
                if (s != null) {
                    model.put(s);
                }
                return;
            }
            var r = MqttMessages.raw(message.getPayload(), now);
            if (r == null || !r.hasAir() || props.location() == null || !props.location().equals(r.location())) {
                return;
            }
            Integer room = sensors.roomFor(r.spot());
            sensors.seen(r.devEui(), r.spot(), r.profile(), room, r.at(), r.co2(), r.temperatureC(), r.humidityPct());
            if (room == null) {
                return;
            }
            Integer ppm = r.co2() == null ? null : (int) Math.round(r.co2());
            intake.direct(new SensorReading("mqtt-" + r.devEui() + "-" + r.fCnt() + "-" + r.at().getEpochSecond(),
                    room, "CO2", null, ppm, r.temperatureC(), r.humidityPct(), r.at()));
        } catch (RuntimeException e) {
            // 한 줄이 이상해도 구독은 계속
            log.warn("MQTT 메시지 처리 실패: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT 연결 끊김, 다시 붙는 중");
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // 보내지 않음
    }

    @Override
    public void destroy() {
        try {
            if (client != null) {
                if (client.isConnected()) {
                    client.disconnect(2_000);
                }
                client.close();
            }
        } catch (MqttException e) {
            log.debug("MQTT 닫기 실패", e);
        }
    }
}
