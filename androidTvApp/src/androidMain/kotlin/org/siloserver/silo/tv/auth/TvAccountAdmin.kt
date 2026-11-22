package org.siloserver.silo.tv.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.koin.compose.koinInject
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionPhase
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.tv.BuildConfig

/**
 * The profiles allowed to sign out or change account on this TV, built in from
 * the git-ignored local.properties (`silo.accountAdminProfileIds`, comma
 * separated). Empty means no restriction: every profile may, as upstream.
 */
internal val accountAdminProfileIds: Set<String> = BuildConfig.ACCOUNT_ADMIN_PROFILE_IDS
    .split(',')
    .map { it.trim().lowercase() }
    .filter { it.isNotEmpty() }
    .toSet()

/** Whether account actions are limited to some profiles on this build. */
internal val accountActionsRestricted: Boolean get() = accountAdminProfileIds.isNotEmpty()

/** Whether [profileId] may sign out or change account. */
internal fun mayManageAccount(profileId: String?): Boolean =
    !accountActionsRestricted || profileId?.trim()?.lowercase() in accountAdminProfileIds

/**
 * Whether the acting profile may sign out or change account, kept current
 * across profile switches. False until the profile is known on a restricted
 * build, so the actions never flash for a profile that may not use them.
 */
@Composable
internal fun rememberMayManageAccount(
    tokenManager: TokenManager = koinInject(),
    identityTransitions: IdentityTransitionBarrier = koinInject(),
): Boolean {
    if (!accountActionsRestricted) return true
    val allowed by produceState(initialValue = false, tokenManager, identityTransitions) {
        identityTransitions.transitions
            .filter { it.phase == IdentityTransitionPhase.DID_CHANGE }
            .map { }
            .onStart { emit(Unit) }
            .collect { value = mayManageAccount(tokenManager.getProfileId()) }
    }
    return allowed
}
