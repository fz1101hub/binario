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
    fun `watch manifest protects local data and declares launcher and tile metadata`() {
        val manifest = File(repositoryRoot, "wear/src/main/AndroidManifest.xml").readText()
        val extractionRules = File(repositoryRoot, "wear/src/main/res/xml/data_extraction_rules.xml").readText()

        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:taskAffinity=\"it.binario.app.wear.task.main\""))
        assertTrue(manifest.contains("androidx.wear.tiles.PREVIEW"))
        assertTrue(manifest.contains("android:resource=\"@drawable/tile_preview\""))
        assertTrue(extractionRules.contains("<device-transfer>"))
        assertTrue(extractionRules.contains("domain=\"sharedpref\" path=\".\""))
        assertTrue(File(repositoryRoot, "wear/src/main/res/drawable/tile_preview.xml").isFile)
        assertTrue(File(repositoryRoot, "wear/src/main/res/drawable-round/tile_preview.xml").isFile)
    }

    @Test
    fun `privacy copy describes fail closed watch revocation`() {
        val strings = File(repositoryRoot, "app/src/main/res/values/strings.xml").readText()

        assertTrue(strings.contains("Disattivare la condivisione elimina il DataItem e la cache locale del watch"))
        assertFalse(strings.contains("non cancella la cache già sul watch"))
    }

    @Test
    fun `snapshot revocation targets every creator node for the exact data path`() {
        assertEquals("wear://*/binario/wear/snapshot", WearSnapshotPublisher.SNAPSHOT_DELETE_URI)
    }

    @Test
    fun `snapshot revocation is persisted and retried before publishing is enabled`() {
        val publisher = File(repositoryRoot, "app/src/main/java/it/binario/app/wear/WearSnapshotPublisher.kt").readText()
        val viewModel = File(repositoryRoot, "app/src/main/java/it/binario/app/TrainViewModel.kt").readText()

        assertTrue(publisher.contains("putBoolean(KEY_REVOCATION_PENDING, true)"))
        assertTrue(publisher.contains("val intent = operationGate.revokePublications()"))
        assertTrue(publisher.contains("if (!operationGate.isCurrent(intent)) return@runExclusive false"))
        assertTrue(publisher.contains("suspendCoroutine { continuation ->"))
        assertTrue(publisher.contains("if (wasPending && !deleteSnapshot(context)) return@runExclusive false"))
        assertTrue(publisher.contains("remove(KEY_REVOCATION_PENDING)"))
        assertTrue(viewModel.contains("WearSnapshotPublisher.persistRevocationIntent(getApplication())"))
        assertTrue(viewModel.indexOf("if (!enabled) WearSnapshotPublisher.persistRevocationIntent") < viewModel.indexOf("integrationSettings = integrationSettings.copy(wearSnapshotSharingEnabled = enabled)"))
        assertTrue(viewModel.contains("WearSnapshotPublisher.clear(getApplication())"))
        assertTrue(viewModel.contains("WearSnapshotPublisher.enable(getApplication())"))
    }

    @Test
    fun `snapshot publication is urgent before its short TTL expires`() {
        val publisher = File(repositoryRoot, "app/src/main/java/it/binario/app/wear/WearSnapshotPublisher.kt").readText()

        assertTrue(publisher.contains("asPutDataRequest().setUrgent()"))
    }

    @Test
    fun `widget configurator suppresses live model startup effects`() {
        val configurator = File(repositoryRoot, "app/src/main/java/it/binario/app/widget/WidgetConfigureActivity.kt").readText()
        val viewModel = File(repositoryRoot, "app/src/main/java/it/binario/app/TrainViewModel.kt").readText()

        assertTrue(configurator.contains("TrainViewModel(application, enableStartupEffects = false)"))
        assertTrue(viewModel.contains("if (enableStartupEffects)"))
        assertTrue(viewModel.contains("if (enableStartupEffects && refreshRequestId == 0L && hasActiveRoute) refresh()"))
    }

    @Test
    fun `phone refresh listener is path scoped`() {
        val manifest = File(repositoryRoot, "app/src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("com.google.android.gms.wearable.MESSAGE_RECEIVED"))
        assertTrue(manifest.contains("android:pathPrefix=\"/binario/wear/request-refresh\""))
    }
}
