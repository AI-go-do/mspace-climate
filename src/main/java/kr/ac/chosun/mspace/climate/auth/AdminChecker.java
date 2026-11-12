package kr.ac.chosun.mspace.climate.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 관리자인지는 core가 정한다(권한 검사는 core, 4장 안전 규칙). 그 사람 토큰 그대로 core 관리자 API를 한 번 불러
 * 200이면 관리자, 403이면 아님. 같은 회원은 5분 동안 기억한다. climate는 공용·관리자 토큰을 갖지 않는다.
 */
@Component
public class AdminChecker {

    static final Duration REMEMBER = Duration.ofMinutes(5);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final URI probe;
    private final Clock clock;
    private final Map<Long, Remembered> cache = new ConcurrentHashMap<>();

    public AdminChecker(@Value("${climate.core-uri:http://core:8080}") String coreUri, Clock clock) {
        this.probe = URI.create(coreUri + "/api/admin/rooms/environment?minutes=1");
        this.clock = clock;
    }

    /** 관리자가 아니면 403 ADMIN_ONLY */
    public void requireAdmin(long memberId, String authorization) {
        Instant now = clock.instant();
        Remembered r = cache.get(memberId);
        if (r == null || r.until.isBefore(now)) {
            r = new Remembered(ask(authorization), now.plus(REMEMBER));
            cache.put(memberId, r);
        }
        if (!r.admin) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_ONLY", "관리자만 할 수 있어요");
        }
    }

    private boolean ask(String authorization) {
        try {
            HttpResponse<Void> res = http.send(HttpRequest.newBuilder(probe).timeout(Duration.ofSeconds(3))
                    .header("Authorization", authorization).GET().build(), HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() == 200) {
                return true;
            }
            if (res.statusCode() == 403 || res.statusCode() == 401) {
                return false;
            }
        } catch (java.io.IOException e) {
            // 아래에서 503
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CORE_UNAVAILABLE", "관리자 확인을 못 했어요. 잠시 뒤 다시");
    }

    private record Remembered(boolean admin, Instant until) {
    }
}
