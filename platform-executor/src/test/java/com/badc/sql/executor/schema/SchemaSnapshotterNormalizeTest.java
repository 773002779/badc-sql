package com.badc.sql.executor.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 指纹规范化规则（纯函数，不起库）。
 *
 * <p>MySQL 的 {@code SHOW CREATE TABLE} 输出是服务端生成的规范文本，因此"同一结构不同格式"这件事在真库上
 * <b>造不出来</b>，而"只差大小写的真实变更"也造不出稳定的复现（要 ALTER 再抓）。两类边界都只能在这里钉住：
 * 只有空白/换行差异应当被归一，<b>大小写不是噪声</b>。
 */
class SchemaSnapshotterNormalizeTest {

    private static final String CANONICAL = """
            CREATE TABLE `t_order` (
              `id` bigint NOT NULL,
              `remark` varchar(64) DEFAULT NULL,
              PRIMARY KEY (`id`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""";

    private static final String DEFAULT_ABC = """
            CREATE TABLE `t_default` (
              `code` varchar(8) NOT NULL DEFAULT 'ABC'
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""";

    private static final String COMMENT_MIXED_CASE = """
            CREATE TABLE `t_comment` (
              `code` varchar(8) NOT NULL COMMENT 'Order Remark'
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""";

    private static final String AUTO_INCREMENT_3 = """
            CREATE TABLE `t_auto` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              PRIMARY KEY (`id`)
            ) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4""";

    @Test
    void crlf_and_lf_produce_the_same_fingerprint() {
        assertThat(fingerprint(CANONICAL.replace("\n", "\r\n"))).isEqualTo(fingerprint(CANONICAL));
    }

    @Test
    void trailing_whitespace_does_not_change_the_fingerprint() {
        String padded = CANONICAL.replace("\n", "   \n") + "\n\n\t";

        assertThat(fingerprint(padded)).isEqualTo(fingerprint(CANONICAL));
    }

    /** 规范化只该动空白：没空白差异的文本必须原样保留（含大小写）。 */
    @Test
    void normalisation_keeps_every_character_of_the_ddl() {
        assertThat(SchemaSnapshotter.normalize(CANONICAL)).isEqualTo(CANONICAL);
    }

    /** C1 回归：只差字面量大小写的 DEFAULT 是真实变更，不能从指纹里消失。 */
    @Test
    void a_default_value_differing_only_by_case_changes_the_fingerprint() {
        assertThat(fingerprint(DEFAULT_ABC.replace("'ABC'", "'abc'")))
                .isNotEqualTo(fingerprint(DEFAULT_ABC));
    }

    /** C1 回归：只改大小写的 COMMENT 同样是真实变更。 */
    @Test
    void a_comment_differing_only_by_case_changes_the_fingerprint() {
        assertThat(fingerprint(COMMENT_MIXED_CASE.replace("'Order Remark'", "'order remark'")))
                .isNotEqualTo(fingerprint(COMMENT_MIXED_CASE));
    }

    /** I1 回归：自增计数随插入增长，不属于结构，必须在规范化时剔除。 */
    @Test
    void the_auto_increment_counter_is_not_part_of_the_structure() {
        String otherCounter = AUTO_INCREMENT_3.replace("AUTO_INCREMENT=3 ", "AUTO_INCREMENT=999 ");
        String withoutCounter = AUTO_INCREMENT_3.replace(" AUTO_INCREMENT=3", "");

        assertThat(fingerprint(otherCounter)).isEqualTo(fingerprint(AUTO_INCREMENT_3));
        assertThat(fingerprint(withoutCounter))
                .as("表从没插过数据时服务端不输出 AUTO_INCREMENT=N，两次指纹仍须一致")
                .isEqualTo(fingerprint(AUTO_INCREMENT_3));
    }

    /** 真正的结构变化必须被看见，否则漂移检测形同虚设。 */
    @Test
    void an_added_column_changes_the_fingerprint() {
        String added = CANONICAL.replace(
                "`remark` varchar(64) DEFAULT NULL,",
                "`remark` varchar(64) DEFAULT NULL,\n  `extra` int DEFAULT NULL,");

        assertThat(fingerprint(added)).isNotEqualTo(fingerprint(CANONICAL));
    }

    private static String fingerprint(String createSql) {
        return SnapshotPaths.sha256Hex(SchemaSnapshotter.normalize(createSql));
    }
}
