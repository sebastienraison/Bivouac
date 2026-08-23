package com.bivouac.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Une branche d'un choix posé à l'utilisateur, sous forme de carte : icône, intitulé, et la phrase
 * qui dit ce que l'option fait vraiment. Partagé entre le choix d'univers (RIC-104) et le choix de
 * lecture d'un lot de fichiers (RIC-41) — ces deux dialogues posent la même sorte de question, et
 * les voir dans deux habillages différents était exactement le mélange de styles à éviter.
 *
 * Un bouton ordinaire ne convenait pas : ces options ont besoin d'une ligne d'explication sous leur
 * intitulé, et rien dans un libellé de bouton n'accueille une seconde ligne de registre différent.
 *
 * [recommended] met en avant le cas le plus fréquent sans l'imposer : les autres branches restent
 * au même niveau de lisibilité, seul le fond change.
 */
@Composable
fun ChoiceOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    recommended: Boolean = false,
) {
    val shape = RoundedCornerShape(14.dp)
    val background = if (recommended) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val borderColor =
        if (recommended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val contentColor =
        if (recommended) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val subtitleColor =
        if (recommended) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (recommended) {
                        Color.White.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = contentColor)
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = contentColor)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
