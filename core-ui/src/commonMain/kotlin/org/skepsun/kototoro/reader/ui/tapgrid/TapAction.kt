package org.skepsun.kototoro.reader.ui.tapgrid

/** What a tap-grid area does; [color] tints the area in the configuration grid (RGB, no alpha). */
enum class TapAction(
    val color: Int,
) {

    PAGE_NEXT(0x8BFF00),
    PAGE_PREV(0xFF4700),
    CHAPTER_NEXT(0x327E49),
    CHAPTER_PREV(0x7E1218),
    TOGGLE_UI(0x3D69C5),
    SHOW_MENU(0xAA1AC5),
}
