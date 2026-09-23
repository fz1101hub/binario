package it.binario.app.wear

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearDataLayerConfigurationTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun `phone and watch share application id`() {
        val phone = File(repositoryRoot, "app/build.gradle.kts").readText()
        val watch = File(repositoryRoot, "wear/build.gradle.kts").readText()
        val expression = Regex("applicationId\\s*=\\s*\\\"([^\\\"]+)\\\"")

        assertEquals("it.binario.app", expression.find(phone)?.groupValues?.get(1))
        assertEquals("it.binario.app", expression.find(watch)?.groupValues?.get(1))
    }

    @Test
    fun `watch listener is scoped and has no network or fake bind permission`() {
        val manifest = File(repositoryRoot, "wear/src/main/AndroidManifest.xml").readText()

        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("com.google.android.gms.wearable.BIND_LISTENER"))
        assertTrue(manifest.contains("com.google.android.gms.wearable.DATA_CHANGED"))
        assertTrue(manifest.contains("android:scheme=\"wear\""))
        assertTrue(manifest.contains("android:pathPrefix=\"/binario/wear/snapshot\""))
        assertTrue(manifest.contains("com.google.android.wearable.standalone\" android:value=\"false\""))
    }

    @Test
    fun `phone refresh listener is path scoped`() {
        val manifest = File(repositoryRoot, "app/src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("com.google.android.gms.wearable.MESSAGE_RECEIVED"))
        assertTrue(manifest.contains("android:pathPrefix=\"/binario/wear/request-refresh\""))
    }
}
