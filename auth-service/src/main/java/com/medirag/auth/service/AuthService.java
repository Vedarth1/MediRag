package com.medirag.auth.service;

import com.medirag.auth.dto.AuthResponse;
import com.medirag.auth.dto.LoginRequest;
import com.medirag.auth.dto.RegisterRequest;
import com.medirag.auth.entity.User;
import com.medirag.auth.repository.UserRepository;
import com.medirag.auth.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    public AuthResponse register(RegisterRequest request) {

        // 1. Check if email already exists
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already registered: " + request.getEmail());
        }

        // 2. Build and save the user (password is hashed here)
        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .role(User.Role.valueOf(request.getRole().toUpperCase()))
                .build();

        User saved = userRepository.save(user);

        // 3. Issue JWT immediately after registration
        String token = jwtUtil.generateToken(saved.getEmail(), saved.getRole().name(), saved.getId());

        // 4. Store token in Redis (key = "jwt:<email>", TTL = 24h)
        redisTemplate.opsForValue().set(
                "jwt:" + saved.getEmail(),
                token,
                24, TimeUnit.HOURS
        );

        return AuthResponse.builder()
                .token(token)
                .email(saved.getEmail())
                .firstName(saved.getFirstName())
                .role(saved.getRole().name())
                .expiresIn(86400000L)
                .build();
    }

    public AuthResponse login(LoginRequest request) {

        // 1. Find user by email
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Invalid email or password"));

        // 2. Verify password against BCrypt hash
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid email or password");
        }

        // 3. Generate new JWT
        String token = jwtUtil.generateToken(user.getEmail(), user.getRole().name(), user.getId());

        // 4. Store/refresh in Redis
        redisTemplate.opsForValue().set(
                "jwt:" + user.getEmail(),
                token,
                24, TimeUnit.HOURS
        );

        return AuthResponse.builder()
                .token(token)
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .role(user.getRole().name())
                .expiresIn(86400000L)
                .build();
    }

    public void logout(String email) {
        // Delete the token from Redis — immediately invalidates the session
        redisTemplate.delete("jwt:" + email);
    }
}
