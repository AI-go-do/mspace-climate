package kr.ac.chosun.mspace.climate.appliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import kr.ac.chosun.mspace.climate.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClimateFacadeTest {

    final MutableClock clock = new MutableClock(Instant.parse("2026-11-12T06:00:00Z"));
    final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("기종이 달라도(가상·삼성 적외선·LG 적외선) 같은 말 '냉방 25도' 하나로 켠다")
    void sameCommandDifferentBrands() throws Exception {
        // given: 1번 방 삼성, 2번 방 LG 적외선 노드, 3번 방 가상
        List<String> got = new CopyOnWriteArrayList<>();
        HttpServer node = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        node.createContext("/ac", ex -> {
            got.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        node.start();
        String url = "http://127.0.0.1:" + node.getAddress().getPort();
        ClimateFacade facade = new ClimateFacade(List.of(
                new IrAircon("ac-1", 1, "SAMSUNG", url, http),
                new IrAircon("ac-2", 2, "LG", url, http),
                new VirtualAppliance("ac-3", 3, ApplianceKind.AIRCON)), Duration.ofMinutes(30), 100, clock);

        // when
        facade.cool(1, 25, "테스트");
        facade.cool(2, 25, "테스트");
        facade.cool(3, 25, "테스트");
        node.stop(0);

        // then: 브랜드마다 다른 리모컨 신호는 어댑터가 만든다
        assertThat(got).containsExactly(
                "{\"protocol\":\"SAMSUNG_AC\",\"power\":\"ON\",\"mode\":\"COOL\",\"temp\":25}",
                "{\"protocol\":\"LG\",\"power\":\"ON\",\"mode\":\"COOL\",\"temp\":25}");
        assertThat(facade.states(3).get(0).power()).isEqualTo(ApplianceCommand.Power.ON);
        assertThat(facade.recentActions(10)).hasSize(3).allMatch(a -> a.by().equals("AUTO"));
    }

    @Test
    @DisplayName("스마트 플러그 공기청정기는 Tasmota 명령으로 켜고 끈다")
    void tasmota() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        HttpServer plug = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        plug.createContext("/cm", ex -> {
            got.add(ex.getRequestURI().getQuery());
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        plug.start();
        ClimateFacade facade = new ClimateFacade(List.of(new TasmotaPlug("p-1", 1, ApplianceKind.PURIFIER, "샤오미",
                "http://127.0.0.1:" + plug.getAddress().getPort(), http)), Duration.ofMinutes(30), 100, clock);

        facade.set(1, ApplianceKind.PURIFIER, true, "테스트");
        facade.set(1, ApplianceKind.PURIFIER, false, "테스트");
        plug.stop(0);

        assertThat(got).containsExactly("cmnd=Power On", "cmnd=Power Off");
    }

    @Test
    @DisplayName("이미 같은 상태면 다시 보내지 않는다")
    void noRepeat() {
        ClimateFacade facade = virtual();
        assertThat(facade.cool(1, 25, "a")).isTrue();
        assertThat(facade.cool(1, 25, "a")).isFalse();
        assertThat(facade.recentActions(10)).hasSize(1);
    }

    @Test
    @DisplayName("사람이 손으로 끈 기기는 30분 동안 오디가 다시 켜지 않는다")
    void manualHold() {
        ClimateFacade facade = virtual();
        facade.command("ac-1", ApplianceCommand.off(), "ADMIN", "관리자");
        clock.advance(Duration.ofMinutes(10));
        assertThat(facade.cool(1, 25, "오디")).isFalse();
        clock.advance(Duration.ofMinutes(21));
        assertThat(facade.cool(1, 25, "오디")).isTrue();
    }

    @Test
    @DisplayName("기기에 닿지 않으면 상태는 그대로 두고 실패를 조치 기록에 남긴다")
    void unreachable() {
        ClimateFacade facade = new ClimateFacade(List.of(new IrAircon("ac-1", 1, "SAMSUNG", "http://127.0.0.1:1", http)),
                Duration.ofMinutes(30), 100, clock);
        assertThat(facade.cool(1, 25, "오디")).isFalse();
        assertThat(facade.states(1).get(0).power()).isEqualTo(ApplianceCommand.Power.OFF);
        assertThat(facade.recentActions(1).get(0).ok()).isFalse();
        assertThatThrownBy(() -> facade.command("ac-1", ApplianceCommand.on(), "ADMIN", "x"))
                .isInstanceOf(ApplianceException.class);
    }

    @Test
    @DisplayName("에어컨을 플러그 어댑터로 잘못 설정하면 켜지 않고 막는다")
    void airconOnPlugRejected() {
        assertThatThrownBy(() -> new TasmotaPlug("x", 1, ApplianceKind.AIRCON, "b", "http://x", http))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ClimateFacade virtual() {
        return new ClimateFacade(List.of(new VirtualAppliance("ac-1", 1, ApplianceKind.AIRCON),
                new VirtualAppliance("purifier-1", 1, ApplianceKind.PURIFIER)), Duration.ofMinutes(30), 100, clock);
    }
}
