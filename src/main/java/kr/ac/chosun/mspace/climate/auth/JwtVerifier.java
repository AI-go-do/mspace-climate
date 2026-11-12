package kr.ac.chosun.mspace.climate.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * core가 HS256으로 서명한 로그인 토큰 확인. 게이트웨이가 먼저 보지만 climate도 한 번 더 본다(core와 같은 기준: 서명·exp·iss).
 */
@Component
public class JwtVerifier {

    private static final int MIN_SECRET_BYTES = 32;

    private final MACVerifier verifier;
    private final String issuer;
    private final Clock clock;

    public JwtVerifier(@Value("${mspace.jwt.secret:}") String secret, @Value("${mspace.jwt.issuer:mspace-core}") String issuer,
                       Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET이 없거나 32바이트보다 짧아요. core와 같은 값을 넣어 주세요");
        }
        try {
            this.verifier = new MACVerifier(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("JWT_SECRET을 못 써요", e);
        }
        this.issuer = issuer;
        this.clock = clock;
    }

    /** Authorization 헤더 → 회원 id. 틀리면 401 */
    public long memberId(String authorization) {
        String token = bearer(authorization);
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(verifier)) {
                throw unauthorized();
            }
            var c = jwt.getJWTClaimsSet();
            Date exp = c.getExpirationTime();
            if (exp == null || !exp.toInstant().isAfter(clock.instant()) || !issuer.equals(c.getIssuer())) {
                throw unauthorized();
            }
            return Long.parseLong(c.getSubject());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw unauthorized();
        }
    }

    static String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() < 8) {
            throw unauthorized();
        }
        return authorization.substring(7).trim();
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요해요");
    }
}
