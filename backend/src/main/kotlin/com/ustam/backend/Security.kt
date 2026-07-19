package com.ustam.backend

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.auth.jwt.*
import java.util.Date

object JwtConfig {
    // In production this must come from an env var (Render dashboard secret), never hardcoded.
    val secret: String = System.getenv("JWT_SECRET") ?: "dev-only-insecure-secret-change-me"
    const val issuer = "ustam-backend"
    const val audience = "ustam-app"
    private const val accessTokenValidityMs = 60L * 60 * 1000 // 1 hour
    private const val refreshTokenValidityMs = 30L * 24 * 60 * 60 * 1000 // 30 days

    private val algorithm = Algorithm.HMAC256(secret)

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .build()

    fun generateAccessToken(userId: Int): String = JWT.create()
        .withIssuer(issuer)
        .withAudience(audience)
        .withClaim("userId", userId)
        .withClaim("type", "access")
        .withExpiresAt(Date(System.currentTimeMillis() + accessTokenValidityMs))
        .sign(algorithm)

    fun generateRefreshToken(userId: Int): String = JWT.create()
        .withIssuer(issuer)
        .withAudience(audience)
        .withClaim("userId", userId)
        .withClaim("type", "refresh")
        .withExpiresAt(Date(System.currentTimeMillis() + refreshTokenValidityMs))
        .sign(algorithm)
}

object PasswordHasher {
    fun hash(password: String): String = BCrypt.withDefaults().hashToString(12, password.toCharArray())

    fun verify(password: String, hash: String): Boolean =
        BCrypt.verifyer().verify(password.toCharArray(), hash).verified
}
