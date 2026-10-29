package com.badc.sql.executor.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.badc.sql.common.error.BizException;

/**
 * 快照路径的越狱防护。
 *
 * <p>快照引用来自控制面（{@code {systemId}/{datasourceId}/{ticketId}/{changeSeq}}），一旦这里只是
 * "拼字符串"，一个 {@code ../../} 就能让执行器把文件写到根目录之外。表名不参与路径：MySQL 允许表名带
 * 反引号、点号甚至斜杠，把它拼进路径等于把库里的命名变成文件系统指令。
 */
class SnapshotPathsTest {

    private static final String ROOT = "/data/snapshot";

    @Test
    void a_relative_ref_is_joined_under_the_root() {
        assertThat(SnapshotPaths.resolve(ROOT, "1/101/7/1"))
                .isEqualTo(Path.of("/data/snapshot/1/101/7/1"));
    }

    @Test
    void dot_dot_is_refused() {
        assertThatThrownBy(() -> SnapshotPaths.resolve(ROOT, "1/../../etc"))
                .isInstanceOf(BizException.class).hasMessageContaining("路径");
    }

    @Test
    void an_absolute_ref_is_refused() {
        assertThatThrownBy(() -> SnapshotPaths.resolve(ROOT, "/etc/passwd"))
                .isInstanceOf(BizException.class);
    }

    /** 反斜杠在 Windows 上是分隔符，必须比斜杠更早拒绝。 */
    @Test
    void a_backslash_is_refused() {
        assertThatThrownBy(() -> SnapshotPaths.resolve(ROOT, "1\\..\\..\\etc"))
                .isInstanceOf(BizException.class);
    }

    /** 盘符开头的路径在 Windows 上会直接跳出根目录；{@code C:foo} 这种盘符相对路径同样危险。 */
    @Test
    void a_windows_drive_letter_is_refused() {
        assertThatThrownBy(() -> SnapshotPaths.resolve(ROOT, "C:/Windows/system32"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> SnapshotPaths.resolve(ROOT, "C:boot.ini"))
                .isInstanceOf(BizException.class);
    }

    /** 空引用等于把 schema.json 写在根上；快照必须落在子目录里。 */
    @Test
    void a_blank_ref_is_refused() {
        assertThatThrownBy(() -> SnapshotPaths.resolve(ROOT, "")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> SnapshotPaths.resolve(ROOT, "   ")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> SnapshotPaths.resolve(ROOT, null)).isInstanceOf(BizException.class);
    }

    /** 归一化后仍在根目录内的引用是合法的：判定标准是"归一化后仍以根为前缀"，不是"含 .. 就拒"。 */
    @Test
    void a_ref_that_normalises_back_under_the_root_is_allowed() {
        assertThat(SnapshotPaths.resolve(ROOT, "1/../2"))
                .isEqualTo(Path.of("/data/snapshot/2"));
    }

    /** 路径里有非法字符（NUL 在 Windows / Unix 都不允许）：同样是"不合法"，不能漏成 500。 */
    @Test
    void a_ref_with_an_illegal_character_is_refused() {
        assertThatThrownBy(() -> SnapshotPaths.resolve(ROOT, "1/\u0000/2"))
                .isInstanceOf(BizException.class);
    }
}
