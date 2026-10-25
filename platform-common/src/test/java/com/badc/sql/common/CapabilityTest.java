package com.badc.sql.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.badc.sql.common.dto.Capability;

/**
 * 能力探测的纯逻辑部分：由版本字符串推出能力，不连数据库（R13：本地无 5.7 实例）。
 */
class CapabilityTest {

    @Test
    void detects_57_from_a_real_cloud_version_string() {
        Capability cap = Capability.fromVersionString("5.7.44-log", false);

        assertThat(cap.version()).isEqualTo(DbVersion.MYSQL_5_7);
        assertThat(cap.versionString()).isEqualTo("5.7.44-log");
        assertThat(cap.supportsExplainAnalyze()).isFalse();
        assertThat(cap.supportsInstantDdl()).isFalse();
    }

    @Test
    void explain_analyze_needs_8_0_18() {
        assertThat(Capability.fromVersionString("8.0.16", false).supportsExplainAnalyze()).isFalse();
        assertThat(Capability.fromVersionString("8.0.18", false).supportsExplainAnalyze()).isTrue();
        assertThat(Capability.fromVersionString("8.0.36", false).supportsExplainAnalyze()).isTrue();
    }

    @Test
    void instant_ddl_needs_8_0_12() {
        assertThat(Capability.fromVersionString("8.0.11", false).supportsInstantDdl()).isFalse();
        assertThat(Capability.fromVersionString("8.0.12", false).supportsInstantDdl()).isTrue();
    }

    @Test
    void binlog_flag_comes_from_the_server_not_the_version() {
        assertThat(Capability.fromVersionString("8.0.36", true).binlogEnabled()).isTrue();
        assertThat(Capability.fromVersionString("8.0.36", false).binlogEnabled()).isFalse();
    }

    @Test
    void unknown_version_string_is_treated_as_unsupported_rather_than_guessed() {
        Capability cap = Capability.fromVersionString("mariadb-10.11", false);

        assertThat(cap.version()).isNull();
        assertThat(cap.supportsExplainAnalyze()).isFalse();
        assertThat(cap.supportsInstantDdl()).isFalse();
    }

    @Test
    void malformed_version_does_not_throw() {
        assertThat(Capability.fromVersionString(null, false).version()).isNull();
        assertThat(Capability.fromVersionString("", false).version()).isNull();
        assertThat(Capability.fromVersionString("8", false).version()).isNull();
    }
}
