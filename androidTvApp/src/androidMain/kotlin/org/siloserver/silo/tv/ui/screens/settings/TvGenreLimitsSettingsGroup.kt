package org.siloserver.silo.tv.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.siloserver.silo.genrelimits.GenreConfigFetchError
import org.siloserver.silo.genrelimits.GenreConfigRepository
import org.siloserver.silo.genrelimits.GenreLimitSurface
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.tv.R
import org.siloserver.silo.tv.ui.components.TvTextInputDialog
import java.text.DateFormat
import java.util.Date

/**
 * Settings › Server › Genre limits: the read token for the household's genre
 * config Worker, the active profile's ID (the key the config is written
 * against), the limit it currently applies, and when the config was last
 * fetched.
 */
@Composable
internal fun TvGenreLimitsSettingsGroup(
    repository: GenreConfigRepository = koinInject(),
    tokenManager: TokenManager = koinInject(),
    firstFocusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by repository.state.collectAsState()
    val limit by remember(repository) { repository.limitFlow(GenreLimitSurface.BROWSE) }.collectAsState(initial = null)
    val profileId by produceState<String?>(initialValue = null, tokenManager) { value = tokenManager.getProfileId() }
    var showTokenDialog by remember { mutableStateOf(false) }

    SettingsGroup(title = stringResource(R.string.tv_genreLimits_title)) {
        SettingsInfoRow(
            label = stringResource(R.string.tv_genreLimits_profileId),
            value = profileId ?: stringResource(R.string.tv_genreLimits_noProfile),
            singleLine = false,
        )
        val copiedMessage = stringResource(R.string.tv_genreLimits_copied)
        SettingsActionRow(
            label = stringResource(R.string.tv_genreLimits_copyProfileId),
            enabled = profileId != null,
            onClick = {
                val id = profileId ?: return@SettingsActionRow
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("Silo profile ID", id)) // i18n-ignore
                Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
            },
        )
        SettingsInfoRow(
            label = stringResource(R.string.tv_genreLimits_activeLimit),
            value = limit?.allowedGenres?.joinToString(", ") ?: stringResource(R.string.tv_genreLimits_noLimit),
            singleLine = false,
        )
        if (limit?.searchAllGenres == true) {
            SettingsFooterText(text = stringResource(R.string.tv_genreLimits_searchAll))
        }
        SettingsInfoRow(
            label = stringResource(R.string.tv_genreLimits_lastFetched),
            value = state.fetchedAtEpochMs?.let {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
            } ?: stringResource(R.string.tv_genreLimits_never),
        )
        SettingsValueRow(
            label = stringResource(R.string.tv_genreLimits_token),
            value = stringResource(if (state.hasToken) R.string.tv_genreLimits_tokenSet else R.string.tv_genreLimits_tokenNotSet),
            onClick = { showTokenDialog = true },
            focusRequester = firstFocusRequester,
        )
        SettingsActionRow(
            label = stringResource(if (state.refreshing) R.string.tv_genreLimits_refreshing else R.string.tv_genreLimits_refresh),
            enabled = state.hasToken && !state.refreshing,
            onClick = { scope.launch { repository.refresh() } },
        )
        state.lastError?.let { error ->
            SettingsFooterText(text = stringResource(error.messageRes()))
        }
        SettingsFooterText(text = stringResource(R.string.tv_genreLimits_footer))
    }

    if (showTokenDialog) {
        TvTextInputDialog(
            title = stringResource(R.string.tv_genreLimits_tokenDialogTitle),
            label = stringResource(R.string.tv_genreLimits_tokenDialogLabel),
            confirmLabel = stringResource(R.string.tv_genreLimits_save),
            allowBlank = true,
            keyboardType = KeyboardType.Password,
            onConfirm = { token ->
                repository.setToken(token)
                showTokenDialog = false
                if (token.isNotBlank()) scope.launch { repository.refresh() }
            },
            onDismiss = { showTokenDialog = false },
        )
    }
}

private fun GenreConfigFetchError.messageRes(): Int = when (this) {
    GenreConfigFetchError.NO_TOKEN -> R.string.tv_genreLimits_error_noToken
    GenreConfigFetchError.UNAUTHORIZED -> R.string.tv_genreLimits_error_unauthorized
    GenreConfigFetchError.HTTP -> R.string.tv_genreLimits_error_http
    GenreConfigFetchError.NETWORK -> R.string.tv_genreLimits_error_network
    GenreConfigFetchError.INVALID_DOCUMENT -> R.string.tv_genreLimits_error_invalid
}
