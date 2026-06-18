package com.siamakerlab.vibecoder.server.projects

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VersionNameResolverTest {

    @Test
    fun `resolve literal versionName`() {
        val gradle = """
            android {
                defaultConfig {
                    versionName = "1.2.3"
                }
            }
        """.trimIndent()
        assertEquals("1.2.3", VersionNameResolver.resolve(gradle, emptyMap()))
    }

    @Test
    fun `resolve versionName with val reference`() {
        val gradle = """
            val appVersion = "2.0.1"
            android {
                defaultConfig {
                    versionName = appVersion
                }
            }
        """.trimIndent()
        assertEquals("2.0.1", VersionNameResolver.resolve(gradle, emptyMap()))
    }

    @Test
    fun `resolve versionName with string interpolation`() {
        val gradle = """
            val major = 1
            val minor = 5
            android {
                defaultConfig {
                    versionName = "v${'$'}major.${'$'}minor"
                }
            }
        """.trimIndent()
        assertEquals("v1.5", VersionNameResolver.resolve(gradle, emptyMap()))
    }

    @Test
    fun `resolve versionName with versionProps`() {
        val gradle = """
            android {
                defaultConfig {
                    versionName = "${'$'}{versionProps["VERSION_NAME"] ?: "0.1.0"}"
                }
            }
        """.trimIndent()
        val props = mapOf("VERSION_NAME" to "3.2.1")
        assertEquals("3.2.1", VersionNameResolver.resolve(gradle, props))
    }

    @Test
    fun `resolve versionName with versionProps elvis fallback`() {
        val gradle = """
            android {
                defaultConfig {
                    versionName = "${'$'}{versionProps["MISSING"] ?: "0.0.1"}"
                }
            }
        """.trimIndent()
        assertEquals("0.0.1", VersionNameResolver.resolve(gradle, emptyMap()))
    }

    @Test
    fun `resolve versionName with multi-level indirection`() {
        val gradle = """
            val vMajor = versionProps["V_MAJOR"] ?: "1"
            val vMinor = "2"
            val appVersion = "${'$'}vMajor.${'$'}vMinor"
            android {
                defaultConfig {
                    versionName = appVersion
                }
            }
        """.trimIndent()
        val props = mapOf("V_MAJOR" to "4")
        assertEquals("4.2", VersionNameResolver.resolve(gradle, props))
    }

    @Test
    fun `resolve returns null for unresolvable expression`() {
        val gradle = """
            android {
                defaultConfig {
                    versionName = someUndefinedVar
                }
            }
        """.trimIndent()
        assertNull(VersionNameResolver.resolve(gradle, emptyMap()))
    }

    @Test
    fun `resolve returns null when no versionName present`() {
        val gradle = """
            android {
                defaultConfig {
                    applicationId = "com.example.test"
                }
            }
        """.trimIndent()
        assertNull(VersionNameResolver.resolve(gradle, emptyMap()))
    }

    @Test
    fun `resolve ignores commented out versionName`() {
        val gradle = """
            android {
                defaultConfig {
                    // versionName = "1.0.0"
                    versionName = "2.0.0"
                }
            }
        """.trimIndent()
        assertEquals("2.0.0", VersionNameResolver.resolve(gradle, emptyMap()))
    }

    @Test
    fun `resolve groovy style versionName`() {
        val gradle = """
            android {
                defaultConfig {
                    versionName "1.2.3"
                }
            }
        """.trimIndent()
        assertEquals("1.2.3", VersionNameResolver.resolve(gradle, emptyMap()))
    }
}
