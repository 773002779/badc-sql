package com.badc.sql.control.query;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.RawResult;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

/**
 * 通过 HTTP 调执行器（控制面 / 执行器分离）。
 *
 * <p>超时设置：连接 2s（执行器在同机房，连不上就是真不可用），读 70s（要大于执行器的 60s 语句超时，
 * 否则控制面先放弃、执行器还在跑）。
 */
@Component
public class HttpExecutorClient implements ExecutorClient {

    private final RestClient client;

    public HttpExecutorClient(@Value("${badcsql.executor.base-url:http://127.0.0.1:8081}") String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(70));
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Override
    public RawResult query(ConnConfig conn, String sql, int maxRows) {
        try {
            RawResult result = client.post()
                    .uri("/internal/v1/query")
                    .body(new QueryCall(conn, sql, maxRows))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return response.bodyTo(RawResult.class);
                        }
                        // 把执行器的 {code,message} 原样带回去：用户需要看到"表不存在/语法错/超时"，
                        // 而不是被统一糊成"执行器不可用"。
                        throw ExecutorErrors.toBizException(
                                response.getStatusCode().value(), new String(
                                        response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    });
            if (result == null) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "执行器返回空结果");
            }
            return result;
        } catch (ResourceAccessException e) {
            // 只有连不上/读超时才是"执行器不可用"。
            throw new BizException(ErrorCode.INTERNAL_ERROR, "执行器不可用，请稍后重试", e);
        }
    }

    /** 执行器的查询入参。 */
    public record QueryCall(ConnConfig conn, String sql, int maxRows) {
    }
}
