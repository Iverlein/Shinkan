package shinkan.app.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.iverlein.shinkan.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import shinkan.app.admin.PolicyStore
import shinkan.core.Pin
import shinkan.core.PinGate

/** What the PIN page is for (SPEC §9.1): set a new PIN, typed twice, or enter the one set. */
enum class PinPurpose { SET, ENTER }

/**
 * The PIN page (SPEC §9.1): a keypad of its own, so the page needs no keyboard
 * and works the same in both orientations. [PinPurpose.SET] takes the PIN twice
 * and stores its hash; [PinPurpose.ENTER] checks it against the stored hash,
 * with the wait of [PinGate] after wrong attempts shown as a countdown. The
 * attempt is counted before the hash is checked, so closing the page mid-check
 * does not skip it. [onDone] runs once the PIN is set or right; [onCancel],
 * when given, adds a way back.
 */
@Composable
fun PinPage(
    store: PolicyStore,
    purpose: PinPurpose,
    title: String,
    onDone: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    var typed by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var wait by remember { mutableLongStateOf(remainingWait(store)) }
    val scope = rememberCoroutineScope()
    if (onCancel != null) BackHandler(onBack = onCancel)

    // The countdown: one tick a second while a wait is running.
    LaunchedEffect(wait > 0) {
        while (wait > 0) {
            delay(1000)
            wait = remainingWait(store)
        }
    }

    val prompt = when {
        wait > 0 -> stringResource(R.string.pin_wait, wait)
        purpose == PinPurpose.SET && first == null -> stringResource(R.string.pin_set_first, PinGate.MIN_LENGTH)
        purpose == PinPurpose.SET -> stringResource(R.string.pin_set_again)
        else -> stringResource(R.string.pin_enter)
    }

    fun submit() {
        val pin = typed
        typed = ""
        when (purpose) {
            PinPurpose.SET -> {
                if (!PinGate.acceptable(pin.toCharArray())) {
                    message = R.string.pin_too_short
                    first = null
                } else if (first == null) {
                    first = pin
                    message = null
                } else if (first != pin) {
                    message = R.string.pin_mismatch
                    first = null
                } else {
                    busy = true
                    scope.launch {
                        val hash = withContext(Dispatchers.Default) { Pin.hash(pin.toCharArray()) }
                        store.pinHash = hash
                        store.pinWrong = 0
                        busy = false
                        onDone()
                    }
                }
            }
            PinPurpose.ENTER -> {
                val stored = store.pinHash ?: return onDone()
                if (remainingWait(store) > 0) return
                busy = true
                val wrongBefore = store.pinWrong
                store.pinWrong = wrongBefore + 1
                store.pinLastAttempt = System.currentTimeMillis()
                scope.launch {
                    val right = withContext(Dispatchers.Default) { Pin.verify(pin.toCharArray(), stored) }
                    busy = false
                    if (right) {
                        store.pinWrong = 0
                        onDone()
                    } else {
                        message = R.string.pin_wrong
                        wait = remainingWait(store)
                    }
                }
            }
        }
    }

    val enabled = !busy && wait == 0L
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val header: @Composable () -> Unit = {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(prompt, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Dots(typed.length)
        Spacer(Modifier.height(12.dp))
        val note = if (busy) R.string.pin_checking else message
        Text(note?.let { stringResource(it) } ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        if (onCancel != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.pin_cancel)) }
        }
    }
    val keypad: @Composable () -> Unit = {
        Keypad(
            enabled = enabled,
            canSubmit = typed.isNotEmpty(),
            onDigit = { if (typed.length < MAX_LENGTH) { typed += it; message = null } },
            onBack = { typed = typed.dropLast(1) },
            onSubmit = ::submit,
        )
    }
    if (landscape) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f)) { header() }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { keypad() }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            header()
            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { keypad() }
        }
    }
}

private const val MAX_LENGTH = 12

private fun remainingWait(store: PolicyStore): Long =
    PinGate.remaining(store.pinWrong, store.pinLastAttempt, System.currentTimeMillis())

/** One dot per digit typed; the digits themselves are never shown. */
@Composable
private fun Dots(count: Int) {
    Row(Modifier.height(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(count) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onBackground))
        }
        if (count == 0) Box(Modifier.size(14.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
    }
}

@Composable
private fun Keypad(enabled: Boolean, canSubmit: Boolean, onDigit: (Char) -> Unit, onBack: () -> Unit, onSubmit: () -> Unit) {
    val rows = listOf("123", "456", "789")
    val backLabel = stringResource(R.string.pin_backspace)
    Column(Modifier.widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { d -> Key(d.toString(), enabled) { onDigit(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("⌫", enabled, Modifier.semantics { contentDescription = backLabel }, onBack)
            Key("0", enabled) { onDigit('0') }
            Key(stringResource(R.string.pin_ok), enabled && canSubmit, onClick = onSubmit)
        }
    }
}

@Composable
private fun Key(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.width(88.dp).height(52.dp)) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}
