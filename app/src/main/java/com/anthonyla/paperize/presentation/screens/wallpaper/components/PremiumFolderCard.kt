package com.anthonyla.paperize.presentation.screens.wallpaper.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anthonyla.paperize.R
import com.anthonyla.paperize.presentation.screens.wallpaper.PremiumCopyState
import com.anthonyla.paperize.presentation.theme.AppSpacing

/**
 * Card that copies the currently applied wallpaper into the premium folder
 *
 * Styled like the Home/Lock screen toggles above it: highlighted while a premium folder
 * is configured, muted while it is not.
 */
@Composable
fun PremiumFolderCard(
    state: PremiumCopyState,
    folderConfigured: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = if (folderConfigured) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secondaryColor = if (folderConfigured) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val description = when (state) {
        PremiumCopyState.Copying -> stringResource(R.string.premium_copy_in_progress)
        is PremiumCopyState.Success -> state.message
        is PremiumCopyState.Error -> state.message
        PremiumCopyState.Idle ->
            if (folderConfigured) {
                stringResource(R.string.save_to_premium_description)
            } else {
                stringResource(R.string.premium_folder_not_set)
            }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (folderConfigured) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
        ) {
            if (state == PremiumCopyState.Copying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = secondaryColor
                )
            } else {
                Icon(
                    Icons.Default.BookmarkAdd,
                    contentDescription = null,
                    tint = secondaryColor
                )
            }
            Text(
                text = stringResource(R.string.save_to_premium),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = secondaryColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
