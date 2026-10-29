package com.badc.sql.control.query;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import com.badc.sql.common.DbVersion;
import com.badc.sql.common.dto.Capability;
import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ExplainEstimate;
import com.badc.sql.common.dto.RawResult;
import com.badc.sql.common.dto.ValidationResult;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

/**
 * 通过 HTTP 调执行器（控制面 / 执行器分离）。
 *
 * <p>超时设置：连接 2s（执行器在同机房，连不上就是真不可用），读 70s（要大于执行器的 60s 语句超时，
 * 否则控制面先放弃、执行器还在跑）。
 *
 * <p>五个内部调用（query / probe / validate / fingerprint / explain）共用同一套错误还原：非 2xx 一律交给
 * {@link ExecutorErrors} 把执行器的 {@code {code,message}} 还原成 {@link BizException}，
 * 连不上才是"执行器不可用"。各写一套的结果是同一类故障在不同入口给出不同提示，用户无从判断。
 */
@Component
public class HttpExecutorClient implements ExecutorClient {

    private static final String UNAVAILABLE = "执行器不可用，请稍后重试";

    /** 与执行器 {@code InternalAuthFilter} 约定的内部令牌头。 */
    private static final String INTERNAL_TOKEN_HEADER = "X-Badc-Internal-Token";

    private final RestClient client;

    public HttpExecutorClient(
            @Value("${badcsql.executor.base-url:http://127.0.0.1:8081}") String baseUrl,
            @Value("${badcsql.executor.internal-token:${BADCSQL_INTERNAL_TOKEN:}}") String internalToken) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(70));
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(INTERNAL_TOKEN_HEADER, internalToken)
                .requestFactory(factory)
                .build();
    }

    @Override
    public RawResult query(ConnConfig conn, String sql, int maxRows) {
        return post("/internal/v1/query", new QueryCall(conn, sql, maxRows), RawResult.class,
                "执行器返回空结果");
    }

    @Override
    public Capability probe(ConnConfig conn, ConnConfig onlineDdlOrNull) {
        return post("/internal/v1/probe", new ProbeCall(conn, onlineDdlOrNull), Capability.class,
                "执行器返回空的能力探测结果");
    }

    @Override
    public ValidationResult validate(ConnConfig conn, String sql, DbVersion version) {
        // 连接参数不随校验请求下发：判定只依赖版本，下发凭证是多余的暴露面。
        return post("/internal/v1/validate", new ValidateCall(sql, version), ValidationResult.class,
                "执行器返回空的校验结果");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> fingerprint(ConnConfig conn, List<String> tables) {
        // 空 map 是"这些表都不存在"的合法答案，只有响应体为空才是故障；连接/权限问题走异常。
        return (Map<String, String>) post("/internal/v1/fingerprint", new FingerprintCall(conn, tables),
                Map.class, "执行器返回空的指纹结果");
    }

    /**
     * EXPLAIN 行数估算。
     *
     * <p>响应体里的 {@code estimatedRows} 为 {@code -1} 是<b>正常结果</b>（估不出来），不是"0 行"：
     * 只有响应体为空才是故障。这一点与 {@code fingerprint} 的空 map 是同一个坑——把"没算出来"
     * 当成一个合法的零值，风险分级就会少给两级审批。
     */
    @Override
    public ExplainEstimate explain(ConnConfig conn, String sql) {
        return post("/internal/v1/explain", new ExplainCall(conn, sql), ExplainEstimate.class,
                "执行器返回空的估算结果");
    }

    /**
     * 发一个内部调用并把结果映射成 {@code type}。
     *
     * @param emptyResultMessage 执行器 2xx 却没有响应体时的错误消息
     */
    private <T> T post(String uri, Object body, Class<T> type, String emptyResultMessage) {
        T result;
        try {
            result = client.post()
                    .uri(uri)
                    .body(body)
                    .<T>exchange((request, response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return response.bodyTo(type);
                        }
                        // 把执行器的 {code,message} 原样带回去：用户需要看到"表不存在/语法错/超时"，
                        // 而不是被统一糊成"执行器不可用"。
                        throw ExecutorErrors.toBizException(
                                response.getStatusCode().value(),
                                new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                    });
        } catch (ResourceAccessException e) {
            // 只有连不上/读超时才是"执行器不可用"。
            throw new BizException(ErrorCode.INTERNAL_ERROR, UNAVAILABLE, e);
        }
        if (result == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, emptyResultMessage);
        }
        return result;
    }

    /** 执行器的查询入参。 */
    public record QueryCall(ConnConfig conn, String sql, int maxRows) {
    }

    /** 执行器的探测入参；{@code onlineDdlConn} 为空表示该数据源未配置在线 DDL 账号。 */
    public record ProbeCall(ConnConfig conn, ConnConfig onlineDdlConn) {
    }

    /** 执行器的校验入参；{@code dbVersion} 为空表示版本未知，执行器不做版本判定。 */
    public record ValidateCall(String sql, DbVersion dbVersion) {
    }

    /** 执行器的指纹入参；返回的 map 只含实际存在的表，不存在的表不会出现。 */
    public record FingerprintCall(ConnConfig conn, List<String> tables) {
    }

    /** 执行器的 EXPLAIN 入参；执行器只发 {@code explain <sql>}，不会执行原语句。 */
    public record ExplainCall(ConnConfig conn, String sql) {
    }
}
