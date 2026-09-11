package com.khabar.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
import com.khabar.reader.feed.HtmlBlock
import com.khabar.reader.feed.HtmlSpan

/**
 * Draws a parsed article body with ordinary composables.
 *
 * No WebView: this has to render the same whether the phone is online or not, inside the
 * reader's own scroll container, at the reader's own text size.
 */
@Composable
fun HtmlBody(
    blocks: List<HtmlBlock>,
    textScale: Float,
    showImages: Boolean,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val bodyStyle = MaterialTheme.typography.bodyLarge.scaled(textScale)
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline
        )
    )

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is HtmlBlock.Paragraph -> {
                    Text(
                        text = block.spans.annotated(linkStyles, onLinkClick),
                        style = bodyStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(14.dp))
                }

                is HtmlBlock.Heading -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = block.spans.annotated(linkStyles, onLinkClick),
                        style = headingStyle(block.level, textScale),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(10.dp))
                }

                is HtmlBlock.Bullet -> {
                    Row(modifier = Modifier.padding(bottom = 8.dp)) {
                        Text(
                            text = block.marker,
                            style = bodyStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(28.dp)
                        )
                        Text(
                            text = block.spans.annotated(linkStyles, onLinkClick),
                            style = bodyStyle,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                is HtmlBlock.Quote -> {
                    Row(
                        modifier = Modifier
                            .height(IntrinsicSize.Min)
                            .padding(bottom = 14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = block.spans.annotated(linkStyles, onLinkClick),
                            style = bodyStyle.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is HtmlBlock.Code -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp)
                    ) {
                        // Long lines scroll rather than widening the article.
                        Text(
                            text = block.text,
                            style = bodyStyle.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = bodyStyle.fontSize * 0.85f
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            softWrap = false,
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp)
                        )
                    }
                }

                is HtmlBlock.Image -> {
                    if (showImages) {
                        AsyncImage(
                            model = block.url,
                            contentDescription = block.alt,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .padding(bottom = 4.dp),
                            contentScale = ContentScale.FillWidth
                        )
                        val caption = block.alt
                        if (!caption.isNullOrBlank()) {
                            Text(
                                text = caption,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                }

                HtmlBlock.Rule -> {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }
            }
        }
    }
}

/**
 * Links are real [LinkAnnotation]s rather than a tap-position hit test, so Text handles the
 * click, the touch target and accessibility on its own.
 */
private fun List<HtmlSpan>.annotated(
    linkStyles: TextLinkStyles,
    onLinkClick: (String) -> Unit
): AnnotatedString = buildAnnotatedString {
    for (span in this@annotated) {
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            background = if (span.code) Color(0x1A808080) else Color.Unspecified
        )
        val href = span.link
        if (href != null) {
            withLink(
                LinkAnnotation.Url(href, linkStyles) { annotation ->
                    (annotation as? LinkAnnotation.Url)?.let { onLinkClick(it.url) }
                }
            ) {
                withStyle(style) { append(span.text) }
            }
        } else {
            withStyle(style) { append(span.text) }
        }
    }
}

@Composable
private fun headingStyle(level: Int, textScale: Float): TextStyle = when (level) {
    1, 2 -> MaterialTheme.typography.headlineSmall
    3 -> MaterialTheme.typography.titleLarge
    else -> MaterialTheme.typography.titleMedium
}.scaled(textScale)

private fun TextStyle.scaled(scale: Float): TextStyle =
    copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)
