package org.skepsun.kototoro.reader.ui

import com.google.android.material.slider.LabelFormatter
import org.skepsun.kototoro.parsers.util.format

class PageLabelFormatter : LabelFormatter {

    override fun getFormattedValue(value: Float): String {
        return (value + 1).format(0)
    }
}
