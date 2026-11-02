package com.badc.sql.control.user;

/**
 * 角色 → 可见范围。**这是平台里唯一一处把角色名翻译成可见范围的地方。**
 *
 * <p>为什么必须只有一处：可见范围同时决定了工单列表/详情（{@code TicketQueryMapper}）、查询历史
 * （{@code QueryHistoryService}）、授权自查（{@code VisibilityService}）三条读路径返回什么。本切片里
 * "某个角色该看到什么"曾经分散在四个文件里各写一遍 switch，结果每次加角色、改口径都要记得改四处——
 * 漏一处不会编译失败，只会<b>悄悄多给或少给一批数据</b>，是权限洞最典型的产生方式。现在四条读路径都
 * {@link #of(String) 问这里要范围}，SQL 各写各的（表不同，谓词本来就不同），但"谁属于哪个范围"只有这一份。
 *
 * <p>角色取值与 {@code sys_user.role} 的 CHECK 约束一致，<b>大小写敏感</b>：库里存的是大写，
 * 传进 {@code "developer"} 属于未知角色，按 {@link #NONE} 处理而不是猜。
 *
 * <p>未知角色、{@code null}、用户不存在一律 {@link #NONE}（fail-closed）。宁可让人看到空列表来问
 * "我是不是没权限"，也不要因为一个拼错的角色名把全量数据放出去。
 *
 * <h2>本枚举<b>不</b>负责的两件事</h2>
 * <p>别把它们硬塞进来——它们是另一个维度，混在一起会退化成"角色名查表"：
 * <ul>
 *   <li><b>能否写</b>（改连接、改系统结构、建档）：那是 {@code OperatorGuard} 的角色集合 + 系统归属两道闸，
 *       同一个 {@code PRIMARY_OPS} 在这里是"看自己运维的系统"，在那里还要再问"是不是这个系统的"。</li>
 *   <li><b>是不是某系统的运维</b>：{@code TicketReviewMapper.isOperatorOf} 只看 {@code sys_system_operator}
 *       有没有这个人，<b>故意不看角色</b>——归属是数据库里的事实，不是角色推导出来的结论；给它加角色过滤会让
 *       "角色改了但归属还在"的人突然审不了单。</li>
 * </ul>
 */
public enum RoleScope {

    /** 只能看到自己名下的记录（研发：只读自己提交的工单、自己的查询历史）。 */
    SELF,

    /** 能看到自己运维的系统范围内的记录（主运维 / 备运维 / 组长）。 */
    OPERATED_SYSTEMS,

    /** 全部可见（管理员：负责全局治理）。 */
    ALL,

    /** 什么都看不到（未知角色、用户不存在）。 */
    NONE;

    /**
     * 把角色名翻译成可见范围。
     *
     * @param role {@code sys_user.role} 的值；{@code null}（用户不存在）按 {@link #NONE}
     */
    public static RoleScope of(String role) {
        if (role == null) {
            return NONE;
        }
        return switch (role) {
            case "DEVELOPER" -> SELF;
            case "PRIMARY_OPS", "BACKUP_OPS", "OPS_LEAD" -> OPERATED_SYSTEMS;
            case "ADMIN" -> ALL;
            default -> NONE;
        };
    }

    /**
     * 该范围的过滤谓词是否需要把 {@code userId} 绑进去。
     *
     * <p>存在的意义是让"谓词里几个 {@code ?}"和"绑几个参数"由同一个 switch 决定。曾经的做法是检查
     * 谓词字符串里有没有 {@code "1 = 0"} 来猜参数个数——那种写法在谓词改一个字之后就会静默错位。
     */
    public boolean needsUserId() {
        return this == SELF || this == OPERATED_SYSTEMS;
    }
}
