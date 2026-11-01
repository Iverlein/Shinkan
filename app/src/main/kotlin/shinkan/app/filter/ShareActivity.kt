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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.iverlein.shinkan.R
import shinkan.app.ui.ShinkanTheme
import shinkan.core.net.Link

/**
 * "Block in Shinkan" from any app's share sheet (SPEC §6.2): the shared
 * link is read, what will be blocked is shown, one tap confirms. The PIN
 * gate is ROADMAP block 2; a video's creator lookup, block 9.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val link = Link.parse(text)
        val store = BlocklistStore(this)
        setContent {
            ShinkanTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp)) {
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
