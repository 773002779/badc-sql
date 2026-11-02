package com.badc.sql.control.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.badc.sql.control.user.RoleScope;

/**
 * 查询历史的可见性谓词。
 *
 * <p>为什么要有这组测试：{@code /api/v1/history} 的端到端覆盖只有两条用例（研发看自己、运维看不到研发），
 * {@link RoleScope#ALL} 与 {@link RoleScope#NONE} 从没被执行过。这两条恰好又是最危险的两条：
 * <ul>
 *   <li>{@code NONE} 的谓词若失效（例如被改成空串），未知角色的用户会看到<b>全库</b>查询历史；</li>
 *   <li>{@code ALL} 不绑 {@code userId}，谓词里却多一个 {@code ?}，只有管理员会撞上"参数个数不对"。</li>
 * </ul>
 * 两者在开发环境都不显眼——管理员账号通常不查历史，未知角色根本不存在。
 */
class QueryHistoryFilterTest {

    @ParameterizedTest
    @EnumSource(RoleScope.class)
    void the_predicate_has_exactly_one_placeholder_per_bound_argument(RoleScope scope) {
        String filter = QueryHistoryService.filterFor(scope);
        int placeholders = countPlaceholders(filter);
        int bound = scope.needsUserId() ? 1 : 0;

        assertThat(placeholders)
                .as("%s 的谓词有 %d 个 ?，但会绑定 %d 个参数：%s", scope, placeholders, bound, filter)
                .isEqualTo(bound);
    }

    @Test
    void the_none_scope_must_filter_everything_out_rather_than_return_everything() {
        // 空串拼在 count(*) 后面就是"全表"：fail-open。未知角色不该看到任何一条历史。
        assertThat(QueryHistoryService.filterFor(RoleScope.NONE))
                .as("NONE 的谓词不能为空，否则未知角色能读到全量查询历史")
                .isNotBlank();
    }

    @Test
    void the_all_scope_filters_nothing_because_the_admin_governs_every_system() {
        assertThat(QueryHistoryService.filterFor(RoleScope.ALL)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(RoleScope.class)
    void every_non_empty_predicate_starts_with_where_so_it_can_be_appended_to_the_count_query(RoleScope scope) {
        String filter = QueryHistoryService.filterFor(scope);
        if (filter.isBlank()) {
            return; // ALL：不过滤，直接接 order by
        }
        assertThat(filter.stripLeading())
                .as("%s 的谓词要以 where 开头，否则拼出来的 SQL 没有过滤条件", scope)
                .startsWith("where");
    }

    private static int countPlaceholders(String sql) {
        int count = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                count++;
            }
        }
        return count;
    }
}
