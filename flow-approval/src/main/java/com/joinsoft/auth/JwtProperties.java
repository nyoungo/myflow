package com.joinsoft.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "flow.jwt")
public class JwtProperties {

    /** 签名密钥，生产环境务必覆盖。 */
    private String secret = "flow-approval-default-secret-please-change-in-production";

    /** 过期时间（分钟）。 */
    private long expireMinutes = 720;

    /** 请求头名称。 */
    private String header = "Authorization";

    /** 请求头前缀。 */
    private String prefix = "Bearer ";

    /** 兼容 query 参数传递 token。 */
    private String queryParam = "token";

    public long getExpireSeconds() {
        return expireMinutes * 60L;
    }
}
