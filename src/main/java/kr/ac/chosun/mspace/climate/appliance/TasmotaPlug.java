package kr.ac.chosun.mspace.climate.appliance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 스마트 플러그(Tasmota) 어댑터. 공기청정기·환풍기처럼 전원만 켜고 끄는 기기용.
 * {@code GET <plugUrl>/cm?cmnd=Power%20On}. 정전 뒤 자동 켜짐이 되는 기기여야 전원으로 켜진다(확인 필요).
 */
public final class TasmotaPlug implements Appliance {

    private final String id;
    private final int roomId;
    private final ApplianceKind kind;
    private final String brand;
    private final String plugUrl;
    private final HttpClient http;

    public TasmotaPlug(String id, int roomId, ApplianceKind kind, String brand, String plugUrl, HttpClient http) {
        if (kind == ApplianceKind.AIRCON) {
            throw new IllegalArgumentException("에어컨은 플러그로 켜지 않아요(적외선 어댑터를 쓰세요): " + id);
        }
        this.id = id;
        this.roomId = roomId;
        this.kind = kind;
        this.brand = brand;
        this.plugUrl = plugUrl.endsWith("/") ? plugUrl.substring(0, plugUrl.length() - 1) : plugUrl;
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
        return kind;
    }

    @Override
    public String brand() {
        return brand;
    }

    @Override
    public String adapter() {
        return "tasmota";
    }

    @Override
    public void send(ApplianceCommand c) {
        String power = c.power() == ApplianceCommand.Power.ON ? "On" : "Off";
        URI uri = URI.create(plugUrl + "/cm?cmnd=Power%20" + power);
        try {
            HttpResponse<Void> res = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() / 100 != 2) {
                throw new ApplianceException("플러그 응답 " + res.statusCode());
            }
        } catch (java.io.IOException e) {
            throw new ApplianceException("플러그에 닿지 않음", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApplianceException("플러그 기다리다 멈춤", e);
        }
    }
}
