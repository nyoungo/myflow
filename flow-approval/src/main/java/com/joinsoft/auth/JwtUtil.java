package com.joinsoft.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.joinsoft.common.system.vo.LoginUser;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * JWT 签发与校验（java-jwt 3.11.0）。
 */
@Component
public class JwtUtil {

    private final JwtProperties properties;
    private final Algorithm algorithm;
    private final JWTVerifier verifier;

    public JwtUtil(JwtProperties properties) {
        this.properties = properties;
        this.algorithm = Algorithm.HMAC256(properties.getSecret());
        this.verifier = JWT.require(algorithm).build();
    }

    public String sign(LoginUser user) {
        Date now = new Date();
        Date expiresAt = new Date(now.getTime() + properties.getExpireSeconds() * 1000L);
        return JWT.create()
                .withSubject(user.getId())
                .withClaim("username", user.getUsername())
                .withClaim("realname", user.getRealname())
                .withIssuedAt(now)
                .withExpiresAt(expiresAt)
                .sign(algorithm);
    }

    public DecodedJWT verify(String token) {
        return verifier.verify(token);
    }

    public String getUserId(String token) {
        return verify(token).getSubject();
    }
}
