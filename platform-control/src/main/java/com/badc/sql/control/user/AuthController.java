package com.badc.sql.control.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.security.JwtService;

/**
 * 密码登录：校验 BCrypt 哈希并签发 JWT。
 *
 * <p>登录失败（用户不存在 / 密码错误 / 参数为空）统一返回 401 且响应体完全一致；用户不存在时同样执行一次
 * BCrypt 校验，避免通过响应时间枚举用户名。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String INVALID_CREDENTIALS_MESSAGE = "用户名或密码错误";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final String dummyPasswordHash;

    public AuthController(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        // 用户不存在时用它陪跑一次 BCrypt，保证失败路径耗时与密码错误一致。
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        String loginName = request.loginName() == null ? "" : request.loginName().trim();
        String password = request.password();
        if (loginName.isEmpty() || password == null || password.isEmpty()) {
            throw invalidCredentials();
        }

        Optional<SysUser> user = userMapper.findByLoginName(loginName);
        boolean passwordMatches = passwordEncoder.matches(
                password, user.map(SysUser::passwordHash).orElse(dummyPasswordHash));
        if (user.isEmpty() || !passwordMatches) {
            throw invalidCredentials();
        }

        SysUser authenticated = user.get();
        return new LoginResponse(
                jwtService.issue(authenticated.id(), authenticated.loginName()),
                authenticated.id(),
                authenticated.loginName());
    }

    private static BizException invalidCredentials() {
        return new BizException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS_MESSAGE);
    }

    /** 登录请求体。 */
    public record LoginRequest(String loginName, String password) {
    }

    /** 登录响应体：token + 用户身份。 */
    public record LoginResponse(String token, Long userId, String loginName) {
    }
}
