package com.vitalyart.bydkeyless.update

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRepositoryTest {
    @Test fun semanticVersionsFollowStableAndPrereleaseOrdering() {
        val stable = requireNotNull(SemanticVersion.parse("v1.2.3"))
        val rc = requireNotNull(SemanticVersion.parse("1.2.3-rc.2"))
        val rc10 = requireNotNull(SemanticVersion.parse("1.2.3-rc.10"))
        assertTrue(stable > rc10)
        assertTrue(rc10 > rc)
        assertEquals("1.2.3", stable.toString())
        assertNull(SemanticVersion.parse("1.2"))
        assertNull(SemanticVersion.parse("01.2.3"))
    }

    @Test fun releaseSelectionHonorsChannelDraftsAndCurrentVersion() {
        val releases = JSONArray()
            .put(release("v1.1.0-beta.1", prerelease = true))
            .put(release("v1.0.1"))
            .put(release("v9.0.0", draft = true))
            .put(release("not-a-version"))
        val current = requireNotNull(SemanticVersion.parse("1.0.0"))
        assertEquals("1.0.1", GitHubReleaseRepository.selectRelease(releases, current, false)?.version.toString())
        assertEquals("1.1.0-beta.1", GitHubReleaseRepository.selectRelease(releases, current, true)?.version.toString())
        assertNull(GitHubReleaseRepository.selectRelease(releases, requireNotNull(SemanticVersion.parse("2.0.0")), true))
    }

    @Test fun releaseRequiresMatchingApkAndChecksumAssets() {
        val invalid = release("v1.0.1").also {
            it.getJSONArray("assets").getJSONObject(0).put("name", "another.apk")
        }
        assertNull(
            GitHubReleaseRepository.selectRelease(
                JSONArray().put(invalid), requireNotNull(SemanticVersion.parse("1.0.0")), false,
            ),
        )
    }

    @Test fun repositoryLoadsChecksumAndUsesEtag() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val json = JSONArray().put(release("v1.0.1", baseUrl = server.url("/").toString())).toString()
            server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "release-etag").setBody(json))
            server.enqueue(MockResponse().setResponseCode(200).setBody("${"a".repeat(64)}  BYDKeyless-v1.0.1-arm64-v8a.apk\n"))
            val repository = GitHubReleaseRepository(
                releasesUrl = server.url("releases").toString(),
                acceptedDownloadPrefix = server.url("/").toString(),
            )
            val result = repository.fetchLatest(requireNotNull(SemanticVersion.parse("1.0.0")), false) as ReleaseFetchResult.Found
            assertEquals("release-etag", result.etag)
            assertEquals("a".repeat(64), result.release?.sha256)
            assertEquals("/releases", server.takeRequest().path)
            assertEquals("/checksums", server.takeRequest().path)

            server.enqueue(MockResponse().setResponseCode(304))
            assertEquals(
                ReleaseFetchResult.NotModified,
                repository.fetchLatest(requireNotNull(SemanticVersion.parse("1.0.0")), false, result.etag),
            )
            assertEquals("release-etag", server.takeRequest().getHeader("If-None-Match"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun repositoryExposesGithubHttpErrorDetails() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setHeader("X-RateLimit-Reset", "1789754662")
                    .setBody("{\"message\":\"API rate limit exceeded\"}"),
            )
            val repository = GitHubReleaseRepository(releasesUrl = server.url("releases").toString())
            val failure = runCatching {
                repository.fetchLatest(requireNotNull(SemanticVersion.parse("1.0.0")), false)
            }.exceptionOrNull()

            assertTrue(failure?.message?.contains("HTTP 403") == true)
            assertTrue(failure?.message?.contains("API rate limit exceeded") == true)
            assertTrue(failure?.message?.contains("2026-09-18T18:04:22Z") == true)
        } finally {
            server.shutdown()
        }
    }

    @Test fun checksumParserOnlyAcceptsExactAsset() {
        val expected = "b".repeat(64)
        assertEquals(expected, GitHubReleaseRepository.parseChecksum("$expected *BYDKeyless-v1.0.1-arm64-v8a.apk", "BYDKeyless-v1.0.1-arm64-v8a.apk"))
        assertEquals(expected, GitHubReleaseRepository.parseChecksum("$expected  ./BYDKeyless-v1.0.1-arm64-v8a.apk", "BYDKeyless-v1.0.1-arm64-v8a.apk"))
        assertNull(GitHubReleaseRepository.parseChecksum("$expected other.apk", "BYDKeyless-v1.0.1-arm64-v8a.apk"))
    }

    @Test fun automaticChecksAreThrottledButManualChecksAreNot() {
        val now = 2 * AppUpdateManager.CHECK_INTERVAL_MILLIS
        assertFalse(AppUpdateManager.shouldCheck(now - 1_000L, now, manual = false))
        assertTrue(AppUpdateManager.shouldCheck(now - AppUpdateManager.CHECK_INTERVAL_MILLIS, now, manual = false))
        assertTrue(AppUpdateManager.shouldCheck(now - 1_000L, now, manual = true))
        assertTrue(AppUpdateManager.shouldCheck(now + 1_000L, now, manual = false))
    }

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
        baseUrl: String = "https://github.com/VitalyArt/byd-keyless/releases/download/$tag/",
    ): JSONObject {
        val version = tag.removePrefix("v")
        val downloadBase = baseUrl
        val checksumUrl = if (baseUrl.startsWith("https://github.com")) "${downloadBase}SHA256SUMS.txt" else "${baseUrl}checksums"
        return JSONObject()
            .put("tag_name", tag)
            .put("name", "BYD Keyless $tag")
            .put("html_url", "https://github.com/VitalyArt/byd-keyless/releases/tag/$tag")
            .put("draft", draft)
            .put("prerelease", prerelease)
            .put("assets", JSONArray()
                .put(JSONObject().put("name", "BYDKeyless-v$version-arm64-v8a.apk")
                    .put("browser_download_url", downloadBase + "BYDKeyless-v$version-arm64-v8a.apk"))
                .put(JSONObject().put("name", "SHA256SUMS.txt").put("browser_download_url", checksumUrl)))
    }
}
