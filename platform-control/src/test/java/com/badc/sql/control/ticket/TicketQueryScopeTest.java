package com.badc.sql.control.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.badc.sql.control.user.RoleScope;

/**
 * 工单读路径的可见性谓词。
 *
 * <p>{@link TicketQueryMapper} 的三条查询都靠"谓词 + 参数"配对成立，而配对是按 {@link RoleScope} 分支的：
 * <ul>
 *   <li>{@code count} / {@code page} 用 {@code filter(scope)} + {@code filterArgs(scope)}；</li>
 *   <li>{@code isVisible} 还要在谓词后面再拼 {@code idPredicate(scope)}，多绑一个 {@code ticketId}。</li>
 * </ul>
 *
 * <p>最容易错的是 {@link RoleScope#ALL}：它的 {@code filter} 是空串（不过滤），所以 {@code idPredicate}
 * 必须自己起一个 {@code where}；而 {@code SELF}/{@code OPERATED_SYSTEMS} 已经有 {@code where}，只能接
 * {@code and}。写反了就是 SQL 语法错——而且只有管理员打开某张工单详情时才撞得到。
 *
 * <p>这组断言不依赖数据库，因此四种范围（含端到端用例覆盖不到的 {@code ALL}/{@code NONE}）都被检查到。
 */
class TicketQueryScopeTest {

    @ParameterizedTest
    @EnumSource(RoleScope.class)
    void count_and_page_bind_exactly_as_many_arguments_as_the_predicate_has_placeholders(RoleScope scope) {
        int placeholders = countPlaceholders(TicketQueryMapper.filter(scope));
        int bound = TicketQueryMapper.filterArgs(scope, 7L).length;

        assertThat(placeholders)
                .as("%s：谓词 %d 个 ?，绑定 %d 个参数", scope, placeholders, bound)
                .isEqualTo(bound);
    }

    @ParameterizedTest
    @EnumSource(RoleScope.class)
    void is_visible_binds_one_more_argument_for_the_ticket_id(RoleScope scope) {
        String predicate = TicketQueryMapper.filter(scope) + TicketQueryMapper.idPredicate(scope);
        int placeholders = countPlaceholders(predicate);
        int bound = TicketQueryMapper.filterArgs(scope, 7L).length + 1; // +1 = ticketId

        assertThat(placeholders)
                .as("%s：详情谓词 %d 个 ?，绑定 %d 个参数（含 ticketId）", scope, placeholders, bound)
                .isEqualTo(bound);
    }

    @ParameterizedTest
    @EnumSource(RoleScope.class)
    void the_id_predicate_joins_onto_whatever_the_scope_predicate_left(RoleScope scope) {
        // 不能靠数 where 的个数：OPERATED_SYSTEMS 的 exists 子查询里本来就有一个 where。
        // 真正的不变量是"接缝怎么拼"：前一段没有 where 就自己起一个，有就接 and。
        String filter = TicketQueryMapper.filter(scope);
        String idPredicate = TicketQueryMapper.idPredicate(scope);

        if (filter.isBlank()) {
            assertThat(idPredicate)
                    .as("%s 没有过滤谓词，详情条件必须自己起 where：%s", scope, idPredicate)
                    .startsWith(" where ");
        } else {
            assertThat(idPredicate)
                    .as("%s 的谓词已有 where，详情条件只能接 and：%s", scope, idPredicate)
                    .startsWith(" and ");
        }
        assertThat(idPredicate).contains("t.id = ?");
    }

    @Test
    void the_none_scope_must_filter_everything_out_rather_than_return_everything() {
        // 空谓词拼在 count(*) 后面就是全表：未知角色会看到所有人的工单。
        assertThat(TicketQueryMapper.filter(RoleScope.NONE)).isNotBlank();
    }

    @Test
    void the_all_scope_leaves_the_list_unfiltered_because_the_admin_governs_every_system() {
        assertThat(TicketQueryMapper.filter(RoleScope.ALL)).isEmpty();
    }

    private static int countPlaceholders(String sql) {
        return countOccurrences(sql, "?");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
