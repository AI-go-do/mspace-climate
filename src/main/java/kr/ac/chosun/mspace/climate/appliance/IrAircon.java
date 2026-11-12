package kr.ac.chosun.mspace.climate.appliance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 적외선 에어컨 어댑터. ESP32 적외선 노드(IRremoteESP8266 IRac, /mnt/project-files/iot-climate-1109 시제품)에
 * {@code POST <nodeUrl>/ac} JSON으로 보내면 노드가 그 브랜드 리모컨 신호를 쏜다.
 * 삼성·LG 등 브랜드 차이는 protocol 글자 하나(IRac 프로토콜 이름)로 끝나서 브랜드마다 클래스를 늘리지 않는다.
 */
public final class IrAircon implements Appliance {

    private final String id;
    private final int roomId;
    private final String brand;
    private final URI node;
    private final HttpClient http;

    public IrAircon(String id, int roomId, String brand, String nodeUrl, HttpClient http) {
        this.id = id;
        this.roomId = roomId;
        this.brand = brand;
        this.node = URI.create(nodeUrl.endsWith("/") ? nodeUrl + "ac" : nodeUrl + "/ac");
        this.http = http;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int roomId() {
        return roomId;
    }

    @Override
    public ApplianceKind kind() {
        return ApplianceKind.AIRCON;
    }

    @Override
    public String brand() {
        return brand;
    }

    @Override
    public String adapter() {
        return "ir";
    }

    /** IRac 프로토콜 이름. 예: SAMSUNG → SAMSUNG_AC, LG → LG */
    String protocol() {
        return switch (brand.toUpperCase()) {
            case "SAMSUNG" -> "SAMSUNG_AC";
            case "LG" -> "LG";
            case "CARRIER" -> "CARRIER_AC";
            case "WINIA", "DAEWOO" -> "DAEWOO";
            default -> brand.toUpperCase();
        };
    }

    @Override
    public void send(ApplianceCommand c) {
        String mode = c.mode() == null ? "COOL" : c.mode().name();
        int temp = c.targetC() == null ? 26 : c.targetC();
        String body = "{\"protocol\":\"%s\",\"power\":\"%s\",\"mode\":\"%s\",\"temp\":%d}"
                .formatted(protocol(), c.power().name(), mode, temp);
        try {
            HttpResponse<Void> res = http.send(HttpRequest.newBuilder(node).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() / 100 != 2) {
                throw new ApplianceException("적외선 노드 응답 " + res.statusCode());
            }
        } catch (java.io.IOException e) {
            throw new ApplianceException("적외선 노드에 닿지 않음", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApplianceException("적외선 노드 기다리다 멈춤", e);
        }
    }
}
