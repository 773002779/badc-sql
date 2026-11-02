package com.badc.sql.control.user;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link RoleScope} 的映射表。
 *
 * <p>本测试的重点不是"五个角色各映射到什么"（那种断言抄一遍实现就没意义了），而是两条约束：
 * <ol>
 *   <li><b>库里允许的每一个角色都必须有范围</b>：角色清单从 {@code V1__baseline.sql} 的
 *       {@code ck_sys_user_role} 里<b>读出来</b>，不手抄。给 CHECK 加一个角色却忘了改 {@code RoleScope}，
 *       那个角色的用户会被判成 {@link RoleScope#NONE}——看不到任何数据。这不会编译失败，只会表现为
 *       "新同事登录后页面全空"。</li>
 *   <li><b>未知输入必须落到 NONE</b>：{@code null}、空串、大小写不符都不能被猜成有权限。</li>
 * </ol>
 */
class RoleScopeTest {

    /** 从建表脚本里抠出 {@code ck_sys_user_role} 列出的角色名。 */
    private static final Pattern ROLE_CONSTRAINT = Pattern.compile(
            "ck_sys_user_role[\\s\\S]{0,120}?role\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");

    @Test
    void every_role_the_schema_allows_has_a_non_empty_scope() throws IOException {
        List<String> schemaRoles = rolesInSchema();

        assertThat(schemaRoles).as("没从建表脚本里解析出角色清单，测试本身失效了").hasSize(5);
        for (String role : schemaRoles) {
            assertThat(RoleScope.of(role))
                    .as("角色 %s 在建表脚本里合法，但 RoleScope 没有给它范围（会落到 NONE）", role)
                    .isNotEqualTo(RoleScope.NONE);
        }
    }

    @Test
    void the_five_roles_map_to_the_intended_scopes() {
        assertThat(RoleScope.of("DEVELOPER")).isEqualTo(RoleScope.SELF);
        assertThat(RoleScope.of("PRIMARY_OPS")).isEqualTo(RoleScope.OPERATED_SYSTEMS);
        assertThat(RoleScope.of("BACKUP_OPS")).isEqualTo(RoleScope.OPERATED_SYSTEMS);
        assertThat(RoleScope.of("OPS_LEAD")).isEqualTo(RoleScope.OPERATED_SYSTEMS);
        assertThat(RoleScope.of("ADMIN")).isEqualTo(RoleScope.ALL);
    }

    @ParameterizedTest
    @EnumSource(value = RoleScope.class, names = {"SELF", "OPERATED_SYSTEMS"})
    void scopes_that_are_limited_to_the_caller_need_the_user_id(RoleScope scope) {
        assertThat(scope.needsUserId()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = RoleScope.class, names = {"ALL", "NONE"})
    void scopes_that_are_not_limited_to_the_caller_do_not_bind_the_user_id(RoleScope scope) {
        // ALL 与 NONE 的谓词里没有 ?：绑了 userId 就会 "参数个数不对"，而且只有那种角色的用户才撞得到。
        assertThat(scope.needsUserId()).isFalse();
    }

    @Test
    void an_unknown_role_gets_nothing_rather_than_being_guessed() {
        assertThat(RoleScope.of(null)).isEqualTo(RoleScope.NONE);
        assertThat(RoleScope.of("")).isEqualTo(RoleScope.NONE);
        assertThat(RoleScope.of("SUPERUSER")).isEqualTo(RoleScope.NONE);
    }

    @Test
    void role_names_are_case_sensitive_so_a_lowercase_typo_does_not_grant_admin() {
        // 库里存的是大写；"admin" 不是 "ADMIN"。写成不区分大小写就等于让拼错的角色名拿到全量数据。
        assertThat(RoleScope.of("admin")).isEqualTo(RoleScope.NONE);
        assertThat(RoleScope.of("developer")).isEqualTo(RoleScope.NONE);
        assertThat(RoleScope.of("Admin")).isEqualTo(RoleScope.NONE);
    }

    /** 建表脚本里 {@code ck_sys_user_role} 允许的角色。 */
    private static List<String> rolesInSchema() throws IOException {
        String sql = readClasspath("/db/migration/V1__baseline.sql");
        Matcher constraint = ROLE_CONSTRAINT.matcher(sql);
        assertThat(constraint.find()).as("建表脚本里找不到 ck_sys_user_role 的 CHECK 子句").isTrue();

        String allowed = constraint.group(1);
        Matcher quoted = QUOTED.matcher(allowed);
        List<String> roles = new ArrayList<>();
        while (quoted.find()) {
            roles.add(quoted.group(1));
        }
        return roles;
    }

    private static String readClasspath(String path) throws IOException {
        try (InputStream in = RoleScopeTest.class.getResourceAsStream(path)) {
            assertThat(in).as("classpath 上找不到 %s", path).isNotNull();
            return new String(in.readAllBytes(), UTF_8);
        }
    }
}
