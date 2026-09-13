package space.zhuoling.fileaccess

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import space.zhuoling.fileaccess.core.transfer.RemoteAccess
import space.zhuoling.fileaccess.preview.PreviewRepository
import space.zhuoling.fileaccess.ui.FileAccessApp
import space.zhuoling.fileaccess.ui.FileAccessTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val updateModel: space.zhuoling.fileaccess.update.UpdateViewModel by viewModels()
    @Inject lateinit var previewRepository: PreviewRepository
    @Inject lateinit var remoteAccess: RemoteAccess

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FileAccessTheme { FileAccessApp(viewModel, previewRepository, remoteAccess, updateModel) } }
    }
}
