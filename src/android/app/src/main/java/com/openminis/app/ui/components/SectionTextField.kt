package com.openminis.app.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Text input for use inside a [SectionCard]. Wraps a BasicTextField in
 * OutlinedTextFieldDefaults.DecorationBox so we can override contentPadding
 * (the default OutlinedTextField overload doesn't expose it) — needed to
 * shrink the row to [SectionDesign.RowMinHeight] (~44dp) and align the
 * inner glyphs with sibling section content.
 *
 * Borders + container fills are transparent so the surrounding
 * [SectionCard] surface is the only visual boundary. Error state keeps a
 * red border as a recoverable affordance.
 *
 * Horizontal contentPadding is 0: every callsite places this field inside
 * a parent ([SettingsCardBlock] or a manually padded Column) that already
 * adds the 16dp horizontal inset. Adding our own 16dp on top would
 * double-indent the text glyphs relative to sibling section headers /
 * description text / radio rows — see T352.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SectionTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    fieldModifier: Modifier = Modifier,
    /**
     * Fill behind the field. The default matches settings cards — but inside
     * a ModalBottomSheet that default is INVISIBLE: M3 sheets use the same
     * surfaceContainerLow as their container, so the field renders as bare
     * borderless text. Sheet callers pass a contrasting fill (typically
     * [SectionDesign.screenBackgroundColor]) to restore the filled-card
     * look the settings screens have.
     */
    containerColor: Color? = null,
    /**
     * [T-android-sheet-textfield-inset] Horizontal inset for the glyphs inside
     * the field.
     *
     * Defaults to 0 because on a SETTINGS screen the field is a full-bleed row
     * inside a section card whose parent already applies the 16dp inset — any
     * padding here would double it and break alignment with sibling rows (T352).
     *
     * That assumption does not hold in a bottom sheet / dialog, where the field
     * is a standalone rounded card supplying its own background: with 0 the text
     * and placeholder sit flush against the card's left edge. Those callers pass
     * a real inset instead of the component changing its default, so no settings
     * screen shifts.
     */
    contentHorizontalPadding: Dp = 0.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color.Transparent,
        unfocusedBorderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
        errorBorderColor = MaterialTheme.colorScheme.error,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
    )
    // Horizontal defaults to 0 so glyphs align with sibling section content
    // (parent already adds the 16dp horizontal inset). See T352. Sheet/dialog
    // callers override via [contentHorizontalPadding].
    val contentPadding = PaddingValues(
        horizontal = contentHorizontalPadding,
        vertical = SectionDesign.RowVerticalPadding,
    )
    val mergedTextStyle = LocalTextStyle.current.merge(textStyle)

    // [T-android-model-id-input-no-hscroll]
    // The legacy `BasicTextField(value: String, ...)` overload reconstructs
    // its internal TextFieldValue from `value` on every recomposition,
    // which forces selection back to the end of the string each time the
    // parent emits a fresh recompose. On a long string in a singleLine
    // field, that means: user taps to move the caret → caret moves
    // briefly → next recompose snaps caret to value.length → the
    // bring-cursor-into-view path never gets to "scroll content sideways
    // so the trailing edge of the text becomes visible" because the
    // caret is always at the END of the (clipped) layout. Net effect: a
    // 60-char model id like "huihui-qwen3.6-35b-…-mtp" has its last
    // characters permanently off-screen and the user can't reach them.
    //
    // Switch to the TextFieldValue overload — selection is owned locally
    // and survives recomposition. The outer caller still passes a plain
    // String; we wrap it, drive onValueChange with the new text only
    // when text actually changed, and re-seed cursor-at-end only when
    // the parent replaces the value out from under us (e.g. screen
    // reset / hydration / a different entry loaded).
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }
    if (fieldValue.text != value) {
        // External value changed (parent assigned a fresh string).
        // Re-seed with cursor at end — matches the legacy String
        // overload's behavior on the same transition.
        fieldValue = TextFieldValue(value, TextRange(value.length))
    }
    Surface(
        color = containerColor ?: SectionDesign.cardColor(),
        shape = SectionDesign.CardShape,
        modifier = modifier.fillMaxWidth(),
    ) {
        BasicTextField(
            value = fieldValue,
            onValueChange = { newValue ->
                fieldValue = newValue
                if (newValue.text != value) onValueChange(newValue.text)
            },
            modifier = Modifier
                .fillMaxWidth()
                // [T-android-settings-ui-md3] #3 standard MD3 single-line text
                // field height (56dp) so input rows match the toggle/value rows.
                .heightIn(min = SectionDesign.TextFieldMinHeight)
                .then(fieldModifier),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = mergedTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interactionSource,
            singleLine = singleLine,
            maxLines = maxLines,
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = fieldValue.text,
                    visualTransformation = visualTransformation,
                    innerTextField = innerTextField,
                    placeholder = placeholder?.let { { Text(it) } },
                    label = null,
                    trailingIcon = trailingIcon,
                    singleLine = singleLine,
                    enabled = enabled,
                    isError = isError,
                    interactionSource = interactionSource,
                    colors = colors,
                    contentPadding = contentPadding,
                    container = {
                        OutlinedTextFieldDefaults.ContainerBox(
                            enabled = enabled,
                            isError = isError,
                            interactionSource = interactionSource,
                            colors = colors,
                            shape = SectionDesign.CardShape,
                        )
                    },
                )
            },
        )
    }
}
