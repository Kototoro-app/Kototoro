package org.skepsun.kototoro.core.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Renders the Markdown subset in [parseMarkdownBlocks] as Compose text: headings, paragraphs, quotes, lists, fenced
 * code and rules, with bold, italic, strikethrough, inline code and clickable links.
 *
 * This is the only Markdown renderer in the app: Android's update page and changelog use it too, so the Windows and
 * Android screens show the same text. Links open through Compose's own URL handling unless [onLinkClick] is given,
 * which is what the tests use.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val colors = markdownColors()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = inlineMarkdown(block.text, style, colors, onLinkClick),
                    style = headingStyle(block.level),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = if (block.level > 2) 2.dp else 6.dp),
                )

                is MarkdownBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text, style, colors, onLinkClick),
                    style = style,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                is MarkdownBlock.Quote -> Row(modifier = Modifier.padding(start = 2.dp)) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(colors.rule, RoundedCornerShape(2.dp)),
                    )
                    Text(
                        text = inlineMarkdown(block.text, style, colors, onLinkClick),
                        style = style,
                        color = colors.quote,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                is MarkdownBlock.ListItem -> Row(
                    modifier = Modifier.padding(start = (block.depth * 14).dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = block.marker,
                        style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(if (block.marker.length > 2) 26.dp else 18.dp),
                    )
                    Text(
                        text = inlineMarkdown(block.text, style, colors, onLinkClick),
                        style = style,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                is MarkdownBlock.Code -> Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = colors.codeBackground,
                ) {
                    Text(
                        text = block.text,
                        style = style.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }

                MarkdownBlock.Rule -> HorizontalDivider(
                    color = colors.rule,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

/** The plain text of [markdown], for tooltips, window titles and test assertions. */
fun markdownPlainText(markdown: String): String = parseMarkdownBlocks(markdown).joinToString("\n") { block ->
    when (block) {
        is MarkdownBlock.Heading -> block.text
        is MarkdownBlock.Paragraph -> block.text
        is MarkdownBlock.Quote -> block.text
        is MarkdownBlock.ListItem -> "${block.marker} ${block.text}"
        is MarkdownBlock.Code -> block.text
        MarkdownBlock.Rule -> ""
    }
}.trim()

/** The colours the Markdown renderers draw with, read once from the theme. */
internal data class MarkdownColors(
    val link: Color,
    val quote: Color,
    val rule: Color,
    val codeBackground: Color,
)

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
    2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    3 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    else -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
}

@Composable
private fun inlineMarkdown(
    text: String,
    style: TextStyle,
    colors: MarkdownColors,
    onLinkClick: ((String) -> Unit)?,
): AnnotatedString {
    val spans = remember(text) { parseMarkdownInline(text) }
    return remember(spans, colors, onLinkClick, style) {
        inlineMarkdownString(spans, style, colors, onLinkClick)
    }
}

private fun inlineMarkdownString(
    spans: List<MarkdownSpan>,
    style: TextStyle,
    colors: MarkdownColors,
    onLinkClick: ((String) -> Unit)?,
): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        when (span) {
            is MarkdownSpan.Text -> withStyle(span.toSpanStyle(style)) { append(span.text) }
            is MarkdownSpan.Link -> {
                val linkStyle = TextLinkStyles(
                    style = SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline),
                )
                // Without an override Compose opens the URL itself (LocalUriHandler); with one, the host picks.
                val annotation: LinkAnnotation = if (onLinkClick == null) {
                    LinkAnnotation.Url(url = span.url, styles = linkStyle)
                } else {
                    LinkAnnotation.Clickable(tag = span.url, styles = linkStyle) { onLinkClick(span.url) }
                }
                withLink(annotation) { append(span.text) }
            }
        }
    }
}

private fun MarkdownSpan.Text.toSpanStyle(style: TextStyle): SpanStyle = SpanStyle(
    fontWeight = if (bold) FontWeight.SemiBold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    textDecoration = if (strikeThrough) TextDecoration.LineThrough else null,
    fontFamily = if (code) FontFamily.Monospace else style.fontFamily,
)

/**
 * The same subset as [MarkdownText], flattened into one annotated string so that a caller which limits the lines
 * itself - the expandable description on the details page - still gets bold text, bullets, code spans and links.
 *
 * Blocks are separated by a line break instead of a gap and single line breaks stay line breaks, because a
 * description is typed line by line. A rule becomes a run of box-drawing characters, as there is no divider to
 * place inside a text block; everything else renders as [MarkdownText] renders it.
 */
@Composable
fun markdownAnnotatedString(
    markdown: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
): AnnotatedString {
    val colors = markdownColors()
    val blocks = remember(markdown) { parseMarkdownBlocks(markdownKeepingLineBreaks(markdown)) }
    return remember(blocks, style, colors) { markdownAnnotatedString(blocks, style, colors) }
}

internal fun markdownAnnotatedString(
    blocks: List<MarkdownBlock>,
    style: TextStyle,
    colors: MarkdownColors,
): AnnotatedString = buildAnnotatedString {
    blocks.forEach { block ->
        if (length > 0) append('\n')
        when (block) {
            is MarkdownBlock.Heading -> {
                val heading = style.copy(fontWeight = FontWeight.SemiBold)
                append(inlineMarkdownString(parseMarkdownInline(block.text), heading, colors, null))
            }

            is MarkdownBlock.Paragraph -> append(
                inlineMarkdownString(parseMarkdownInline(block.text), style, colors, null),
            )

            is MarkdownBlock.Quote -> withStyle(SpanStyle(color = colors.quote)) {
                append("\u258F ")
                append(inlineMarkdownString(parseMarkdownInline(block.text), style, colors, null))
            }

            is MarkdownBlock.ListItem -> {
                append("  ".repeat(block.depth))
                append(block.marker)
                append(' ')
                append(inlineMarkdownString(parseMarkdownInline(block.text), style, colors, null))
            }

            is MarkdownBlock.Code -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground),
            ) {
                append(block.text)
            }

            MarkdownBlock.Rule -> withStyle(SpanStyle(color = colors.rule)) { append("\u2500".repeat(RULE_LENGTH)) }
        }
    }
}

/** How many box-drawing characters a [MarkdownBlock.Rule] takes when it has to fit inside a text block. */
private const val RULE_LENGTH = 12

@Composable
private fun markdownColors(): MarkdownColors {
    val scheme = MaterialTheme.colorScheme
    val link = scheme.primary
    val quote = scheme.onSurfaceVariant
    val rule = scheme.outlineVariant
    val codeBackground = scheme.surfaceVariant.copy(alpha = 0.5f)
    // One instance per theme, so the renderers' remember keys stay stable across recompositions.
    return remember(link, quote, rule, codeBackground) { MarkdownColors(link, quote, rule, codeBackground) }
}
