package com.badc.sql.executor.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 指纹规范化规则（纯函数，不起库）。
 *
 * <p>MySQL 的 {@code SHOW CREATE TABLE} 输出是规范化的，因此"同一结构不同格式"这件事在真库上<b>造不出来</b>：
 * 换行、行尾空白、关键字大小写的差异只能在这里钉住。
 */
class SchemaSnapshotterNormalizeTest {

    private static final String CANONICAL = """
            CREATE TABLE `t_order` (
              `id` bigint NOT NULL,
              `remark` varchar(64) DEFAULT NULL,
              PRIMARY KEY (`id`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""";

    @Test
    void crlf_and_lf_produce_the_same_fingerprint() {
        String crlf = CANONICAL.replace("\n", "\r\n");

        assertThat(fingerprint(crlf)).isEqualTo(fingerprint(CANONICAL));
    }

    @Test
    void trailing_whitespace_does_not_change_the_fingerprint() {
        String padded = CANONICAL.replace("\n", "   \n") + "\n\n\t";

        assertThat(fingerprint(padded)).isEqualTo(fingerprint(CANONICAL));
    }

    /** 5.7 / 8.0 与会话设置会让关键字大小写不同，这类差异不能算漂移。 */
    @Test
    void keyword_case_does_not_change_the_fingerprint() {
        assertThat(fingerprint(CANONICAL.toLowerCase(java.util.Locale.ROOT)))
                .isEqualTo(fingerprint(CANONICAL));
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
