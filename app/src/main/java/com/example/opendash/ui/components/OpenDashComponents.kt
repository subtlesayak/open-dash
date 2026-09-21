package com.example.opendash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.opendash.ui.theme.*

// ---- Shape constants ----
val CardShape    = RoundedCornerShape(20.dp)
val BtnShape     = RoundedCornerShape(16.dp)
val InputShape   = RoundedCornerShape(16.dp)
val ChipShape    = CircleShape
val IconBtnShape = CircleShape

// ---- Card ----
@Composable
fun OpenDashCard(
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    padding: Dp = 18.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val border = BorderStroke(
        1.dp,
        if (glow) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant,
    )

    Surface(
        modifier = modifier,
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (glow) 4.dp else 0.dp,
        shadowElevation = if (glow) 2.dp else 0.dp,
        border = border,
    ) {
        Column(
            modifier = Modifier
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(padding),
            content = content,
        )
    }
}

@Composable
fun OpenDashSurfaceCard(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

// ---- Buttons ----
enum class BtnVariant { Primary, Secondary, Ghost, Danger }
enum class BtnSize { Lg, Md, Sm }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpenDashBtn(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    variant: BtnVariant = BtnVariant.Primary,
    size: BtnSize = BtnSize.Md,
    enabled: Boolean = true,
) {
    val height = when (size) { BtnSize.Lg -> 58.dp; BtnSize.Md -> 50.dp; BtnSize.Sm -> 40.dp }
    val fontSize = when (size) { BtnSize.Lg -> 16.sp; BtnSize.Md -> 15.sp; BtnSize.Sm -> 13.5.sp }
    val iconSize = when (size) { BtnSize.Sm -> 17.dp; else -> 20.dp }
    val enabledContentColor = when (variant) {
        BtnVariant.Primary -> MaterialTheme.colorScheme.onPrimary
        BtnVariant.Secondary -> MaterialTheme.colorScheme.onSurface
        BtnVariant.Ghost -> MaterialTheme.colorScheme.onSurfaceVariant
        BtnVariant.Danger -> MaterialTheme.colorScheme.error
    }
    val resolvedContentColor = if (enabled) {
        enabledContentColor
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = resolvedContentColor,
                modifier = Modifier.size(iconSize),
            )
            Spacer(Modifier.width(9.dp))
        }
        Text(
            text = label,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistFamily,
            color = resolvedContentColor,
            letterSpacing = 0.sp,
            maxLines = 1,
        )
    }

    when (variant) {
        BtnVariant.Primary -> Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = height),
            enabled = enabled,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) { content() }
        BtnVariant.Secondary -> OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = height),
            enabled = enabled,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) { content() }
        BtnVariant.Ghost -> TextButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = height),
            enabled = enabled,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) { content() }
        BtnVariant.Danger -> OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = height),
            enabled = enabled,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
        ) { content() }
    }
}

// ---- Icon button (square rounded) ----
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpenDashIconBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    active: Boolean = false,
    tint: Color? = null,
) {
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier
            .size(size),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = tint ?: if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

// ---- Chip ----
enum class ChipTone { Gold, Warn, Alert, Off, Neutral }

@Composable
fun OpenDashChip(
    label: String,
    tone: ChipTone = ChipTone.Neutral,
    dot: Boolean = false,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    val (container, labelColor, outline) = when (tone) {
        ChipTone.Gold -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.38f))
        ChipTone.Warn -> Triple(Warn.copy(alpha = 0.13f), Warn, Warn.copy(alpha = 0.3f))
        ChipTone.Alert -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error.copy(alpha = 0.32f))
        ChipTone.Off -> Triple(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.outlineVariant)
        ChipTone.Neutral -> Triple(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.outlineVariant)
    }

    AssistChip(
        onClick = {},
        enabled = false,
        modifier = modifier
            .height(32.dp),
        label = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (dot) Box(Modifier.size(7.dp).clip(CircleShape).background(labelColor))
                Text(
                    label, color = labelColor, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistFamily, maxLines = 1,
                )
            }
        },
        leadingIcon = icon?.let {
            { Icon(it, contentDescription = null, tint = labelColor, modifier = Modifier.size(14.dp)) }
        },
        shape = ChipShape,
        border = AssistChipDefaults.assistChipBorder(
            enabled = false,
            borderColor = outline,
            disabledBorderColor = outline,
        ),
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = container,
            disabledLabelColor = labelColor,
            disabledLeadingIconContentColor = labelColor,
        ),
    )
}

// ---- Toggle ----
@Composable
fun OpenDashToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = on,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}

// ---- Segmented control ----
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenDashSegmented(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, opt ->
            SegmentedButton(
                selected = opt == selected,
                onClick = { onSelect(opt) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.primary,
                    inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    activeBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                icon = {},
            ) {
                Text(
                    opt,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistFamily,
                )
            }
        }
    }
}

// ---- Divider ----
@Composable
fun OpenDashDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

// ---- Eyebrow label ----
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        letterSpacing = 0.16.sp,
        fontFamily = GeistMonoFamily,
        fontWeight = FontWeight.Normal,
        modifier = modifier,
    )
}

// ---- List row ----
enum class IconTone { Neutral, Gold, Warn, Alert }

@Composable
fun OpenDashRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTone: IconTone = IconTone.Neutral,
    sub: String? = null,
    right: String? = null,
    rightSub: String? = null,
    accentRight: Boolean = false,
    trailingIcon: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val (iconBg, iconFg) = when (iconTone) {
        IconTone.Gold   -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        IconTone.Alert  -> Alert.copy(alpha = 0.13f) to Alert
        IconTone.Warn   -> Warn.copy(alpha = 0.13f) to Warn
        IconTone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp, horizontal = 4.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (icon != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(iconBg)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
            ) {
                Icon(icon, contentDescription = null, tint = iconFg, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = GeistFamily, letterSpacing = (-0.15).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (right != null || rightSub != null) {
            Column(horizontalAlignment = Alignment.End) {
                if (right != null) {
                    Text(
                        right,
                        color = if (accentRight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.5.sp, fontWeight = FontWeight.Medium,
                        fontFamily = GeistMonoFamily,
                    )
                }
                if (rightSub != null) {
                    Text(rightSub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.5.sp)
                }
            }
        }
        if (trailingIcon || (onClick != null && right == null)) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---- Screen scaffold (scrollable body) ----
@Composable
fun ScreenHeader(
    modifier: Modifier = Modifier,
    title: String? = null,
    eyebrow: String? = null,
    wordmark: Boolean = false,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp, top = 4.dp)
            .defaultMinSize(minHeight = 56.dp),
    ) {
        if (onBack != null) {
            OpenDashIconBtn(
                icon = Icons.Outlined.ChevronLeft,
                onClick = onBack,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            if (wordmark) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "OpenDash",
                        color = MaterialTheme.colorScheme.onSurface, fontFamily = GeistMonoFamily,
                        fontWeight = FontWeight.Bold, fontSize = 21.sp,
                        letterSpacing = 0.14.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
            } else {
                Column {
                    if (eyebrow != null) Eyebrow(eyebrow, Modifier.padding(bottom = 3.dp))
                    if (title != null) {
                        Text(
                            title, color = MaterialTheme.colorScheme.onSurface, fontSize = 30.sp,
                            fontWeight = FontWeight.Bold, fontFamily = GeistFamily,
                            letterSpacing = 0.sp,
                        )
                    }
                }
            }
        }
        trailing?.invoke()
    }
}
