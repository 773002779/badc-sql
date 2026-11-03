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
import com.badc.sql.common.dto.ExecAccountType;
import com.badc.sql.common.dto.ExecuteResult;
import com.badc.sql.common.dto.ExplainEstimate;
import com.badc.sql.common.dto.RawResult;
import com.badc.sql.common.dto.SchemaDiff;
import com.badc.sql.common.dto.SnapshotResult;
import com.badc.sql.common.dto.ValidationResult;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

/**
 * 通过 HTTP 调执行器（控制面 / 执行器分离）。
 *
 * <p>超时设置：连接 2s（执行器在同机房，连不上就是真不可用）；读超时分两档，见下。
 *
 * <p><b>写执行必须用独立的长读超时。</b>执行器对 {@code /internal/v1/execute} 允许 DML 300s / DDL 3600s，
 * 而 {@code TicketExecutionService} 是在异步线程里<b>同步</b>等这个响应。如果它和其余调用共用 70s：
 * <ul>
 *   <li>控制面在 70s 抛 {@code ResourceAccessException}、把工单记成 FAILED（"结果可能已生效"）并置终态；</li>
 *   <li>执行器线程不会因为客户端断开而中止，语句继续跑到 300s/3600s <b>并真的提交</b>；</li>
 *   <li>{@code finally} 在 70s 就 {@code lock.release}——"单数据源写并发 = 1"当场被打破，下一条写语句
 *       与这条仍在跑的语句并发执行。</li>
 * </ul>
 * 所以 {@code /internal/v1/execute} 单独用一个读超时为 {@link #EXECUTE_READ_TIMEOUT_SECONDS} 的 client，
 * 其余六个内部调用继续用 70s（它们背后的查询/元数据语句限时 60s 以内，70s 足够）。
 *
 * <p>七个内部调用（query / probe / validate / fingerprint / explain / snapshot / execute）共用同一套错误还原：
 * 非 2xx 一律交给
 * {@link ExecutorErrors} 把执行器的 {@code {code,message}} 还原成 {@link BizException}，
 * 连不上才是"执行器不可用"。各写一套的结果是同一类故障在不同入口给出不同提示，用户无从判断。
 */
@Component
public class HttpExecutorClient implements ExecutorClient {

    private static final String UNAVAILABLE = "执行器不可用，请稍后重试";

    /** 与执行器 {@code InternalAuthFilter} 约定的内部令牌头。 */
    private static final String INTERNAL_TOKEN_HEADER = "X-Badc-Internal-Token";

    /** 写执行端点：唯一需要长读超时的内部调用。 */
    static final String EXECUTE_URI = "/internal/v1/execute";

    /**
     * 其余内部调用的读超时（秒）：要大于执行器 60s 的只读语句超时，否则控制面先放弃、执行器还在跑。
     */
    static final int DEFAULT_READ_TIMEOUT_SECONDS = 70;

    /**
     * 写执行的读超时（秒）：执行器最长允许 <b>DDL 3600s</b>，读超时必须比它更大，否则控制面先放弃、
     * 执行器还在跑（并且真的提交），同时写锁被提前释放，单数据源写并发 = 1 失效。
     *
     * <p>取 3700 = 3600 + 100s 余量：多出的部分覆盖执行器侧建连、抓快照与本条语句的收尾，确保"控制面先
     * 超时"永远不会发生在"执行器还在跑"之前。调大执行器的 DDL 超时时，这里必须跟着调大。
     */
    static final int EXECUTE_READ_TIMEOUT_SECONDS = 3_700;

    /** 普通内部调用（查询、元数据、EXPLAIN、快照……）的 client。 */
    private final RestClient client;

    /** 写执行专用 client：只有读超时不同。 */
    private final RestClient executeClient;

    public HttpExecutorClient(
            @Value("${badcsql.executor.base-url:http://127.0.0.1:8081}") String baseUrl,
            @Value("${badcsql.executor.internal-token:${BADCSQL_INTERNAL_TOKEN:}}") String internalToken) {
        this.client = buildClient(baseUrl, internalToken, DEFAULT_READ_TIMEOUT_SECONDS);
        this.executeClient = buildClient(baseUrl, internalToken, EXECUTE_READ_TIMEOUT_SECONDS);
    }

    private static RestClient buildClient(String baseUrl, String internalToken, int readTimeoutSeconds) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(INTERNAL_TOKEN_HEADER, internalToken)
                .requestFactory(factory)
                .build();
    }

    /**
     * 该内部调用使用的读超时（秒）。
     *
     * <p>包级可见：单测要直接断言"写执行走长超时、其余仍走 70s"，而不是靠真跑一小时去观察。
     */
    static int readTimeoutSecondsFor(String uri) {
        return EXECUTE_URI.equals(uri) ? EXECUTE_READ_TIMEOUT_SECONDS : DEFAULT_READ_TIMEOUT_SECONDS;
    }

    private RestClient clientFor(String uri) {
        return EXECUTE_URI.equals(uri) ? executeClient : client;
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
     * 抓取执行前结构快照。
     *
     * <p>执行器侧抓取失败会以非 2xx 的 {@code {code,message}} 返回，由 {@link ExecutorErrors} 还原成
     * {@code BizException} 上抛——控制面据此禁止执行。这里<b>不做任何降级</b>：把失败吞成"没有表"就等于
     * 允许在无法确认原始结构的库上做结构变更。
     */
    @Override
    public SnapshotResult snapshot(ConnConfig conn, List<String> tables, String relativeRef) {
        return post("/internal/v1/snapshot", new SnapshotCall(conn, tables, relativeRef), SnapshotResult.class,
                "执行器返回空的快照结果");
    }

    /**
     * 比对两次结构快照。只读执行器上的快照文件，不连目标库，因此不需要连接配置。
     */
    @Override
    public SchemaDiff schemaDiff(String beforeRef, String afterRef) {
        return post("/internal/v1/schema-diff", new SchemaDiffCall(beforeRef, afterRef), SchemaDiff.class,
                "执行器返回空的结构差异");
    }

    /**
     * 执行一条写语句。
     *
     * <p>语句本身失败时执行器仍回 2xx（{@code success=false}），只有入参非法/执行器故障才非 2xx。
     * 因此这里"空响应体"是故障，而 {@code success=false} 是正常结果——两者不能混为一谈。
     *
     * <p>这条调用走 {@link #EXECUTE_READ_TIMEOUT_SECONDS} 的长读超时 client：执行器的语句超时是
     * DML 300s / DDL 3600s，用其余调用的 70s 会让控制面先于执行器放弃（见类注释）。
     */
    @Override
    public ExecuteResult execute(ConnConfig conn, String sql, ExecAccountType type) {
        return post(EXECUTE_URI, new ExecuteCall(conn, sql, type), ExecuteResult.class,
                "执行器返回空的执行结果");
    }

    /**
     * 发一个内部调用并把结果映射成 {@code type}。
     *
     * @param emptyResultMessage 执行器 2xx 却没有响应体时的错误消息
     */
    private <T> T post(String uri, Object body, Class<T> type, String emptyResultMessage) {
        T result;
        try {
            result = clientFor(uri).post()
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

    /** 执行器的快照入参；{@code relativeRef} 是落盘相对路径，执行器负责把它解析在快照根目录之内。 */
    public record SchemaDiffCall(String beforeRef, String afterRef) {
    }

    public record SnapshotCall(ConnConfig conn, List<String> tables, String relativeRef) {
    }

    /** 执行器的写执行入参；{@code accountType} 决定语句超时（DML 300s / DDL 3600s）。 */
    public record ExecuteCall(ConnConfig conn, String sql, ExecAccountType accountType) {
    }
}
