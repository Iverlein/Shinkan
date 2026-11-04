package shinkan.app.filter

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.iverlein.shinkan.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import shinkan.app.admin.PolicyStore
import shinkan.app.player.Creators
import shinkan.app.ui.PinPage
import shinkan.app.ui.PinPurpose
import shinkan.app.ui.ShinkanTheme
import shinkan.core.net.Creator
import shinkan.core.net.Link

/** What the lookup of a shared video has found so far (SPEC §7.3). */
private sealed interface Lookup {
    data object Asking : Lookup
    data object Failed : Lookup
    data class Found(val creator: Creator) : Lookup
}

/**
 * "Block in Shinkan" from any app's share sheet (SPEC §6.2, §8.4): the
 * parent's PIN first (§9.1), when one is set; then the shared link is read,
 * a video's creator looked up (§7.3), what will be blocked shown, one tap
 * confirms.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val link = Link.parse(text)
        val store = BlocklistStore(this)
        val policyStore = PolicyStore(this)
        setContent {
            ShinkanTheme {
                var unlocked by remember { mutableStateOf(policyStore.pinHash == null) }
                var attempt by remember { mutableIntStateOf(0) }
                var lookup by remember { mutableStateOf<Lookup>(Lookup.Asking) }
                // Only after the PIN: the lookup is a request on the parent's behalf (SPEC §7.3).
                LaunchedEffect(unlocked, attempt) {
                    if (!unlocked || link !is Link.Item) return@LaunchedEffect
                    lookup = Lookup.Asking
                    lookup = withContext(Dispatchers.IO) {
                        runCatching { Creators.ofVideo(link.url) }
                            .onFailure { Log.w(TAG, "lookup failed: $it") }
                            .getOrNull()?.let { Lookup.Found(it) } ?: Lookup.Failed
                    }
                }
                val creator = (link as? Link.CreatorLink)?.creator ?: (lookup as? Lookup.Found)?.creator.takeIf { link is Link.Item }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                        if (!unlocked) {
                            PinPage(
                                policyStore, PinPurpose.ENTER, stringResource(R.string.share_label),
                                onDone = { unlocked = true }, onCancel = { finish() },
                            )
                            return@Column
                        }
                        Text(stringResource(R.string.share_label), style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(16.dp))
                        when {
                            link == null -> Text(stringResource(R.string.share_no_link))
                            link is Link.Site -> Text(stringResource(R.string.share_site, link.domain, link.family.sorted().joinToString(", ")))
                            creator != null -> {
                                Text(stringResource(R.string.share_creator, creator.name, creator.platform.title))
                                // The parent is told before the tap (SPEC §6.3).
                                val now = store.blocklist
                                if ((now + creator).holdingFamily(now).sites != now.sites) {
                                    Spacer(Modifier.height(12.dp))
                                    Text(stringResource(R.string.share_family))
                                }
                            }
                            lookup == Lookup.Asking -> Text(stringResource(R.string.share_item_asking))
                            else -> Text(stringResource(R.string.share_item_failed))
                        }
                        Spacer(Modifier.height(24.dp))
                        Row {
                            TextButton(onClick = { finish() }) { Text(stringResource(R.string.share_cancel)) }
                            Spacer(Modifier.width(16.dp))
                            if (link is Link.Site) {
                                Button(onClick = {
                                    var list = store.blocklist
                                    link.family.forEach { list += it }
                                    store.change(this@ShareActivity, list)
                                    finish()
                                }) { Text(stringResource(R.string.share_block)) }
                            }
                            if (creator != null) {
                                Button(onClick = {
                                    store.change(this@ShareActivity, store.blocklist + creator)
                                    finish()
                                }) { Text(stringResource(R.string.share_block)) }
                                // A creator may be allowed instead, for the player's allow mode (SPEC §7.1).
                                Spacer(Modifier.width(16.dp))
                                Button(onClick = {
                                    val list = store.blocklist
                                    store.change(this@ShareActivity, list.copy(allowed = list.allowed + creator))
                                    finish()
                                }) { Text(stringResource(R.string.share_allow)) }
                            }
                            if (link is Link.Item && lookup == Lookup.Failed) {
                                Button(onClick = { attempt++ }) { Text(stringResource(R.string.share_retry)) }
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "ShareActivity"
    }
}
