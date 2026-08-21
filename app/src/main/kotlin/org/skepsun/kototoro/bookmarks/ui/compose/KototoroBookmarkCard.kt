package org.skepsun.kototoro.bookmarks.ui.compose

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.jsoup.Jsoup
import org.skepsun.kototoro.R
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KototoroBookmarkCardThumb(
    item: Bookmark,
    cardStyle: CompactPosterCardStyle,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .height(cardStyle.posterHeight)
    ) {
        AsyncImage(
            model = item.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize()
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.58f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = item.chapterTitle.orEmpty().ifBlank {
                    stringResource(R.string.bookmark_position, item.page + 1)
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (isSelected) {
            Box(modifier = Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)))
            Icon(
                painter = painterResource(id = R.drawable.ic_check),
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.Center).size(32.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)).padding(4.dp)
            )
        }

        CircularProgressIndicator(
            progress = { item.percent },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .size(16.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.White.copy(alpha = 0.5f),
            strokeWidth = 2.dp,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KototoroBookmarkCardNovel(
    item: Bookmark,
    cardStyle: CompactPosterCardStyle,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val previewText = remember(item.imageUrl) { extractTextPreview(context, item.imageUrl) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(12.dp)
            .height(cardStyle.posterHeight)
    ) {
        Text(
            text = item.chapterTitle.orEmpty().ifBlank {
                stringResource(R.string.bookmark_position, item.page + 1)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.weight(1f)) {
            Text(
                text = previewText,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )

            if (isSelected) {
                Box(modifier = Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)))
                Icon(
                    painter = painterResource(id = R.drawable.ic_check),
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.Center).size(32.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)).padding(4.dp)
                )
            }
        }

        CircularProgressIndicator(
            progress = { item.percent },
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 8.dp)
                .size(16.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
            strokeWidth = 2.dp,
        )
    }
}

private fun extractTextPreview(context: Context, imageUrl: String): String {
    return try {
        when {
            imageUrl.startsWith("data:text/html") -> {
                val base64Data = imageUrl.substringAfter("base64,", "")
                if (base64Data.isNotEmpty()) {
                    val htmlBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val html = String(htmlBytes, Charsets.UTF_8)
                    extractTextFromHtml(html)
                } else {
                    context.getString(R.string.bookmark_preview_unavailable)
                }
            }
            imageUrl.contains("<html>") || imageUrl.contains("<!DOCTYPE") -> {
                extractTextFromHtml(imageUrl)
            }
            imageUrl.isEmpty() -> context.getString(R.string.bookmark_position)
            else -> imageUrl.take(200).trim()
        }
    } catch (e: Exception) {
        context.getString(R.string.bookmark_position)
    }
}

private fun extractTextFromHtml(html: String): String {
    return try {
        val doc = Jsoup.parse(html)
        doc.select("script, style").remove()
        val text = doc.body()?.text()?.trim() ?: ""
        text.take(200).replace(Regex("\\s+"), " ")
    } catch (e: Exception) {
        ""
    }
}
