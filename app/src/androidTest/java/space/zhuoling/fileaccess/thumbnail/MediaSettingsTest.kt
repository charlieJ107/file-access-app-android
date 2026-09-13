package space.zhuoling.fileaccess.thumbnail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import space.zhuoling.fileaccess.core.data.SettingsRepository

@RunWith(AndroidJUnit4::class)
class MediaSettingsTest {
    @Test fun viewAndNetworkPreferencesSurviveRepositoryRecreation() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val repository = SettingsRepository(context)
        val previous = repository.observe().first()
        try {
            repository.setBrowserViewMode("grid")
            repository.setMediaThumbnailsUnmeteredOnly(false)
            val saved = SettingsRepository(context).observe().first()
            assertEquals("grid", saved.browserViewMode)
            assertFalse(saved.mediaThumbnailsUnmeteredOnly)
            repository.setBrowserViewMode("list")
            assertEquals("list", repository.observe().first().browserViewMode)
        } finally {
            repository.setBrowserViewMode(previous.browserViewMode)
            repository.setMediaThumbnailsUnmeteredOnly(previous.mediaThumbnailsUnmeteredOnly)
        }
    }
}
