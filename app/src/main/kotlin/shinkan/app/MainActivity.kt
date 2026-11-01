package shinkan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import shinkan.app.admin.DeviceHold
import shinkan.app.admin.PolicyStore
import shinkan.app.ui.ShinkanApp
import shinkan.app.ui.ShinkanTheme

/** The one activity: the child's home, the parent door, or the setup page before the device is held (SPEC §7). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val hold = DeviceHold(this)
        val store = PolicyStore(this)
        setContent {
            ShinkanTheme {
                ShinkanApp(hold, store)
            }
        }
    }
}
