package com.badc.sql.control.query;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.security.CurrentUser;

/**
 * 只读查询接口。
 *
 * <p>研发在这里查数：只返回数据，永远不返回连接信息。写语句会被拒并提示走变更工单。
 */
@RestController
@RequestMapping("/api/v1/query")
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping
    public QueryService.QueryResult query(@RequestBody QueryRequest request) {
        if (request == null || request.datasourceId() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少数据源 ID");
        }
        return queryService.query(CurrentUser.id(), request.datasourceId(), request.sql());
    }

    /** 查询入参。 */
    public record QueryRequest(Long datasourceId, String sql) {
    }
}
