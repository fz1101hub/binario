// Harness compile stubs for androidx.activity.result.contract.ActivityResultContracts.
@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER")

package androidx.activity.result.contract

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult

abstract class ActivityResultContract<I, O> {
    abstract fun createIntent(context: Context, input: I): Intent
    abstract fun parseResult(resultCode: Int, intent: Intent?): O
}

object ActivityResultContracts {
    open class RequestPermission : ActivityResultContract<String, Boolean>() {
        override fun createIntent(context: Context, input: String): Intent = Intent("android.content.pm.action.REQUEST_PERMISSIONS")
        override fun parseResult(resultCode: Int, intent: Intent?): Boolean = false
    }

    open class RequestMultiplePermissions : ActivityResultContract<Array<String>, Map<String, Boolean>>() {
        override fun createIntent(context: Context, input: Array<String>): Intent = Intent("android.content.pm.action.REQUEST_PERMISSIONS")
        override fun parseResult(resultCode: Int, intent: Intent?): Map<String, Boolean> = emptyMap()
    }

    open class OpenDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun createIntent(context: Context, input: Array<String>): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
    }

    open class CreateDocument(private val mimeType: String) : ActivityResultContract<String, Uri?>() {
        override fun createIntent(context: Context, input: String): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mimeType)
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
    }

    open class StartActivityForResult : ActivityResultContract<Intent, ActivityResult>() {
        override fun createIntent(context: Context, input: Intent): Intent = input
        override fun parseResult(resultCode: Int, intent: Intent?): ActivityResult = ActivityResult(resultCode, intent)
    }
}
