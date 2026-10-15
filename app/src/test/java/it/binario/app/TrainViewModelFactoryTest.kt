package it.binario.app

import android.app.Application
import org.junit.Assert.assertNotNull
import org.junit.Test

class TrainViewModelFactoryTest {
    @Test
    fun `default launcher factory can find the Application constructor`() {
        // AndroidViewModelFactory uses Java reflection; a Kotlin default parameter
        // alone does not expose the one-argument constructor that viewModel() needs.
        assertNotNull(TrainViewModel::class.java.getConstructor(Application::class.java))
    }
}
