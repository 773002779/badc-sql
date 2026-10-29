package com.badc.sql.executor.schema;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

/**
 * 结构快照的路径规则。
 *
 * <p><b>快照引用</b>来自控制面（{@code {systemId}/{datasourceId}/{ticketId}/{changeSeq}}），只在
 * {@code badcsql.snapshot.root} 之下展开：归一化后必须仍以根为前缀，且不允许以 {@code /}、{@code \}
 * 或盘符开头。只做字符串拼接的实现会被 {@code ../../} 写到根目录之外。
 *
 * <p><b>表名不参与路径</b>：快照路径完全由这里的 {@code relativeRef} 决定，表名只出现在 SQL 与文件内容里
 * （MySQL 表名可以带反引号、点号甚至斜杠，把它拼进路径等于把库里的命名变成文件系统指令）。
 */
public final class SnapshotPaths {

    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:");

    private SnapshotPaths() {
    }

    /**
     * 把相对引用展开到快照根目录之下。
     *
     * @param root        快照根目录（来自 {@code badcsql.snapshot.root}）
     * @param relativeRef 控制面给的相对引用
     * @return 归一化后的绝对（或根相对）路径，必然位于 {@code root} 之下
     * @throws BizException 引用为空、含反斜杠、以斜杠或盘符开头、或归一化后跳出根目录
     */
    public static Path resolve(String root, String relativeRef) {
        if (root == null || root.isBlank()) {
            // 根目录缺失时不能退化成"当前工作目录"：路径前缀判定会恒真（空路径是所有路径的前缀）。
            throw new BizException(ErrorCode.INTERNAL_ERROR, "快照根目录未配置");
        }
        Path rootPath = Path.of(root).normalize();

        if (relativeRef == null
                || relativeRef.isBlank()
                || relativeRef.contains("\\")
                || relativeRef.startsWith("/")
                || DRIVE_PREFIX.matcher(relativeRef).find()) {
            throw illegal();
        }

        Path relative;
        try {
            relative = Path.of(relativeRef).normalize();
        } catch (InvalidPathException e) {
            // 非法字符（NUL、Windows 下的 * ? : 等）：同样是"不合法"，不能漏成 500。
            throw illegal();
        }
        // "." / ".." / "" 归一化后是空路径，等于把快照写在根目录上；快照必须落在子目录里。
        if (relative.isAbsolute() || relative.toString().isEmpty()) {
            throw illegal();
        }

        Path resolved = rootPath.resolve(relative).normalize();
        if (!resolved.startsWith(rootPath)) {
            throw illegal();
        }
        return resolved;
    }

    /** 表结构指纹的散列函数；包内共用，避免快照与指纹各写一份实现。 */
    static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走到这里说明运行环境被裁剪过。
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    private static BizException illegal() {
        return new BizException(ErrorCode.INVALID_ARGUMENT, "快照路径不合法");
    }
}
