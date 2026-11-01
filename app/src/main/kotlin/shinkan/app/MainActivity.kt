package shinkan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import shinkan.app.admin.DeviceHold
import shinkan.app.admin.PolicyStore
import shinkan.app.ui.ShinkanApp
import shinkan.app.ui.ShinkanTheme

/** The one activity: the child's home, the parent door, or the setup page before the device is held (SPEC §8). */
class MainActivity : ComponentActivity() {
    private lateinit var store: PolicyStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val hold = DeviceHold(this)
        store = PolicyStore(this)
        setContent {
            ShinkanTheme {
                ShinkanApp(hold, store)
            }
        }
    }

    /** The icon chosen on the parent door takes effect once Shinkan has left the screen (SPEC §8.5). */
    override fun onStop() {
        super.onStop()
        LauncherIcon.apply(this, store)
    }
}
