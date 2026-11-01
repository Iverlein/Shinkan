package shinkan.app.filter

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.iverlein.shinkan.R
import shinkan.app.admin.PolicyStore
import shinkan.app.ui.PinPage
import shinkan.app.ui.PinPurpose
import shinkan.app.ui.ShinkanTheme
import shinkan.core.net.Link

/**
 * "Block in Shinkan" from any app's share sheet (SPEC §6.2, §8.4): the
 * parent's PIN first (§9.1), when one is set; then the shared link is read,
 * what will be blocked is shown, one tap confirms. A video's creator lookup
 * is ROADMAP block 6c.
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
                        when (link) {
                            null -> Text(stringResource(R.string.share_no_link))
                            is Link.Site -> Text(stringResource(R.string.share_site, link.domain, link.family.sorted().joinToString(", ")))
                            is Link.CreatorLink -> Text(stringResource(R.string.share_creator, link.creator.name, link.creator.platform.name.lowercase()))
                            is Link.Item -> Text(stringResource(R.string.share_item_later))
                        }
                        Spacer(Modifier.height(24.dp))
                        Row {
                            TextButton(onClick = { finish() }) { Text(stringResource(R.string.share_cancel)) }
                            Spacer(Modifier.width(16.dp))
                            if (link is Link.Site || link is Link.CreatorLink) {
                                Button(onClick = {
                                    var list = store.blocklist
                                    when (link) {
                                        is Link.Site -> link.family.forEach { list += it }
                                        is Link.CreatorLink -> list += link.creator
                                        else -> Unit
                                    }
                                    store.blocklist = list
                                    FilterService.start(this@ShareActivity, reload = true)
                                    finish()
                                }) { Text(stringResource(R.string.share_block)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
