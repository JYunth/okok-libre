package app.okoklibre.ui.design

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.okoklibre.R
import app.okoklibre.data.RoleEntity
import app.okoklibre.ui.theme.LocalAppColors
import app.okoklibre.ui.theme.personColor
import java.io.File

/** Page margin; every full-width edge in the app lines up on this. */
val Gutter: Dp = 20.dp

/** Vertical gap between the sections of a page. */
val SectionGap: Dp = 32.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(
    title: String?,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { title?.let { Text(it, style = MaterialTheme.typography.titleLarge, maxLines = 1) } },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 1.dp, color = LocalAppColors.current.hairline)
}

/** A page section: a plain heading, an optional line of context, then its content. Never a card. */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = Gutter)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            action?.invoke()
        }
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalAppColors.current.muted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        content()
    }
}

/** The app's one list row. 56dp tall so every row is an easy target, figures flush right. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color = Color.Unspecified,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(horizontal = Gutter, vertical = 10.dp),
) {
    val colors = LocalAppColors.current
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 56.dp)
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        }
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = if (valueColor == Color.Unspecified) colors.muted else valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = LocalAppColors.current.raised,
            disabledContentColor = LocalAppColors.current.muted,
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, LocalAppColors.current.hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalAppColors.current.ink),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Set apart on purpose: nothing destructive is ever a neighbour of something safe. */
@Composable
fun DestructiveAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 48.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
        TextButton(
            onClick = onClick,
            colors = ButtonDefaults.textButtonColors(contentColor = LocalAppColors.current.attention),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Text-only options with a sliding underline. Used for ranges and metrics. */
@Composable
fun <T> OptionRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(if (isSelected) colors.raised else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(option) }
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) colors.ink else colors.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The person: their photo, or their initial on their own colour. */
@Composable
fun Avatar(role: RoleEntity, size: Dp, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bitmap = remember(role.avatarPath) {
        role.avatarPath?.takeIf { File(it).exists() }?.let(BitmapFactory::decodeFile)?.asImageBitmap()
    }
    val color = personColor(role.colorIndex, dark)
    Box(
        modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                role.nickname.firstOrNull()?.uppercase().orEmpty(),
                style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
                color = if (dark) Color(0xFF0C0D0F) else Color.White,
            )
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** A centred explanation with one way forward. */
@Composable
fun EmptyState(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodyLarge, color = LocalAppColors.current.muted)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            PrimaryButton(action, onAction)
        }
    }
}

/** Digits roll individually when the value changes; tabular figures keep them in place. */
@Composable
fun RollingNumber(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    Row(modifier.semantics(mergeDescendants = true) {}.clearAndSetSemantics { contentDescription = text }) {
        // Keyed from the right so the decimal stays put when a digit is added on the left.
        text.reversed().mapIndexed { index, char -> index to char }.reversed().forEach { (position, char) ->
            androidx.compose.runtime.key(position) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        val rising = targetState > initialState
                        (slideInVertically(tween(220)) { if (rising) it / 2 else -it / 2 } + fadeIn(tween(160)))
                            .togetherWith(
                                slideOutVertically(tween(220)) { if (rising) -it / 2 else it / 2 } + fadeOut(tween(120)),
                            )
                    },
                    label = "digit",
                ) { digit ->
                    Text(digit.toString(), style = style, color = color)
                }
            }
        }
    }
}
