package br.gov.sp.pcsp.launcher

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppUpdateCheckerTest {

    private val client = OkHttpClient.Builder().build()

    @Test
    fun currentReleaseProducesNoUpdate() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(releaseJson(AppUpdateChecker.APP_VERSION)))

            val result = AppUpdateChecker.checkRelease(client, server.url("/releases/latest").toString())

            val noUpdate = result as AppUpdateChecker.ReleaseCheckResult.NoUpdate
            assertEquals("not_newer", noUpdate.reason)
            assertEquals(AppUpdateChecker.APP_VERSION, noUpdate.tag)
        }
    }

    @Test
    fun newerReleaseWithSigApkProducesAvailable() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    releaseJson(
                        tag = "20991231_001",
                        assetUrl = server.url("/download/sig.apk").toString()
                    )
                )
            )

            val result = AppUpdateChecker.checkRelease(client, server.url("/releases/latest").toString())

            val available = result as AppUpdateChecker.ReleaseCheckResult.Available
            assertEquals("20991231_001", available.tag)
            assertEquals(server.url("/download/sig.apk").toString(), available.apkUrl)
            assertEquals(123L, available.apkSize)
        }
    }

    @Test
    fun olderReleaseProducesNoUpdate() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(releaseJson("20200101_001")))

            val result = AppUpdateChecker.checkRelease(
                client,
                server.url("/releases/latest").toString(),
                installedVersion = "20260824_001"
            )

            assertTrue(result is AppUpdateChecker.ReleaseCheckResult.NoUpdate)
        }
    }

    @Test
    fun invalidJsonAndMissingTagProduceFailures() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{not-json"))
            val invalidJson = AppUpdateChecker.checkRelease(client, server.url("/invalid").toString())
            assertEquals(
                "invalid_json",
                (invalidJson as AppUpdateChecker.ReleaseCheckResult.Failure).code
            )

            server.enqueue(MockResponse().setBody("""{"assets":[]}"""))
            val missingTag = AppUpdateChecker.checkRelease(client, server.url("/missing-tag").toString())
            assertEquals(
                "missing_tag",
                (missingTag as AppUpdateChecker.ReleaseCheckResult.Failure).code
            )
        }
    }

    @Test
    fun missingSigAssetProducesFailure() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    releaseJson(
                        tag = "20991231_001",
                        assetName = "other.apk",
                        assetUrl = server.url("/download/other.apk").toString()
                    )
                )
            )

            val result = AppUpdateChecker.checkRelease(client, server.url("/releases/latest").toString())

            assertEquals(
                "asset_missing",
                (result as AppUpdateChecker.ReleaseCheckResult.Failure).code
            )
        }
    }

    @Test
    fun httpFailureIsDiagnosticAndDoesNotThrow() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))

            val result = AppUpdateChecker.checkRelease(client, server.url("/releases/latest").toString())

            assertEquals(
                "http_503",
                (result as AppUpdateChecker.ReleaseCheckResult.Failure).code
            )
        }
    }

    @Test
    fun downloadSuccessWritesDestinationAndReportsProgress() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("APK-CONTENT"))
            val directory = Files.createTempDirectory("sig-update-test").toFile()
            try {
                val destination = File(directory, "sig.apk")
                var lastProgress = 0L

                val result = AppUpdateChecker.downloadApkToFile(
                    client,
                    server.url("/download/sig.apk").toString(),
                    destination
                ) { downloaded, _ -> lastProgress = downloaded }

                assertEquals(destination, result)
                assertEquals("APK-CONTENT", destination.readText())
                assertTrue(lastProgress > 0L)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun downloadFailureCanBeRetriedWithoutLeavingPartialFile() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setBody("RETRY-APK"))
            val directory = Files.createTempDirectory("sig-update-retry-test").toFile()
            try {
                val destination = File(directory, "sig.apk")
                try {
                    AppUpdateChecker.downloadApkToFile(
                        client,
                        server.url("/download/sig.apk").toString(),
                        destination
                    )
                } catch (error: IllegalStateException) {
                    assertEquals("download_http_500", error.message)
                }
                assertTrue(!destination.exists())

                AppUpdateChecker.downloadApkToFile(
                    client,
                    server.url("/download/sig.apk").toString(),
                    destination
                )
                assertEquals("RETRY-APK", destination.readText())
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun releaseJson(
        tag: String,
        assetName: String = "sig.apk",
        assetUrl: String = "https://example.invalid/sig.apk"
    ): String =
        "{\"tag_name\":\"" + tag + "\",\"assets\":[{\"name\":\"" + assetName +
            "\",\"browser_download_url\":\"" + assetUrl + "\",\"size\":123}]}"
}
