package fixture

/*
 * This file mentions android.graphics.Bitmap and androidx.compose.ui.geometry.Offset
 * only inside a block comment; the guard must not flag documentation.
 */
// And a line comment mentioning org.skepsun.kototoro.reader.ui.compose.Whatever.
import kotlin.math.abs

fun fixtureClean(x: Float): Float = abs(x)
