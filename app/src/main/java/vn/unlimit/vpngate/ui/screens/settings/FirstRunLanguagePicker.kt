package vn.unlimit.vpngate.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.utils.DataUtil

/** One selectable language in the first-run picker. */
data class LanguageOption(val tag: String, val label: String)

/**
 * VpnM Phase 6.3 — first-run language selection.
 *
 * Shown once, on the splash screen, the first time the app is launched. The
 * user picks a language (or defers to the system), and the choice is written to
 * [DataUtil] before [androidx.appcompat.app.AppCompatDelegate.setApplicationLocales]
 * is applied, so the whole UI — including this picker — re-renders in the
 * chosen language.
 *
 * Note the spec's ordering: the *system* language is the default and the user
 * may override it later from Settings, where user selection outranks the
 * system language.
 */
@Composable
fun FirstRunLanguagePicker(
    dataUtil: DataUtil,
    onDone: () -> Unit,
) {
    val options = listOf(
        LanguageOption("fa", stringResource(R.string.language_persian)),
        LanguageOption("en", stringResource(R.string.language_english)),
    )
    val systemTag = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language

    fun apply(tag: String?) {
        dataUtil.setSelectedLocale(tag)
        dataUtil.markLocaleAnswered()
        AppCompatDelegateCompat.setLocales(tag)
        onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.language_pick_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.language_pick_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        options.forEach { option ->
            LanguageRow(
                label = option.label,
                selected = option.tag == systemTag,
                onClick = { apply(option.tag) },
            )
            Spacer(Modifier.height(10.dp))
        }
        // "Follow the system" is the default per the spec, so it must always be
        // reachable and must be what a user who just wants to get on with it
        // ends up with.
        LanguageRow(
            label = stringResource(R.string.language_system_default),
            selected = false,
            onClick = { apply(null) },
        )
    }
}

@Composable
private fun LanguageRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Thin wrapper so the picker does not need the AppCompat import inline. */
private object AppCompatDelegateCompat {
    fun setLocales(tag: String?) {
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
            if (tag.isNullOrBlank()) androidx.core.os.LocaleListCompat.getEmptyLocaleList()
            else androidx.core.os.LocaleListCompat.forLanguageTags(tag),
        )
    }
}
