package it.binario.app.journeys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * A test Main dispatcher for view-model tests that also ends the models' work before Main is reset (audit2
 * ci-tests-build-7). A search started on `viewModelScope` can still be finishing on a background thread and resumes
 * on Main: resetting Main at that moment either prints «Module with the Main dispatcher is missing» from the resumed
 * coroutine, where an assertion could never fail the test, or, under load, fails a correct test with «Dispatchers.Main
 * is used concurrently with setting it». Every model built by a test goes through [track]; [finished] cancels and
 * awaits their work, then resets Main.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelMainDispatcherRule(private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    private val models = mutableListOf<ViewModel>()

    fun <T : ViewModel> track(model: T): T = model.also { synchronized(models) { models += it } }

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        val tracked = synchronized(models) { models.toList().also { models.clear() } }
        try {
            runBlocking { tracked.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
