package org.skepsun.kototoro.ios.shims

import android.content.ContextWrapper
import android.content.res.Resources
import android.media.ImageReader
import android.widget.AbsoluteLayout
import android.app.Presentation
import android.hardware.display.DisplayManager
import android.os.HandlerThread
import android.view.View
import android.view.ViewGroup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BrowserDisplayTest {
    private fun context() = object : ContextWrapper(null) {
        override fun getResources() = Resources.getSystem()
        override fun getApplicationContext() = this
    }

    @Test
    fun `Comix can create and close its browser render target without reading pixels`() {
        val reader = ImageReader.newInstance(390, 844, 1, 2)
        val surface = reader.surface
        assertTrue(surface.isValid)
        reader.setOnImageAvailableListener({ available -> available.acquireLatestImage()?.close() }, null)
        assertNull(reader.acquireLatestImage())
        reader.setOnImageAvailableListener(null, null)
        reader.close()
        assertFalse(surface.isValid)
        assertThrows(IllegalStateException::class.java) { reader.acquireLatestImage() }
        reader.close()
    }

    @Test
    fun `an unattached browser view has no parent`() {
        assertNull(AbsoluteLayout(context()).parent)
    }

    @Test
    fun `Comix presentation mounts its view and removal releases its display reader and drain thread`() {
        val context = context()
        val reader = ImageReader.newInstance(1080, 2340, 1, 2)
        val surface = reader.surface
        val display = DisplayManager().createVirtualDisplay("browser", 1080, 2340, 480, surface, 10)
        val presentation = Presentation(context, display.display)
        val view = AbsoluteLayout(context)
        val drain = HandlerThread("browser-drain-test")
        var shown = 0
        var hidden = 0
        IosBrowserPresentation.setHost(object : IosBrowserPresentation.Host {
            override fun show(content: View, width: Int, height: Int, densityDpi: Int) {
                assertSame(view, content)
                assertSame(context, content.context)
                assertEquals(listOf(1080, 2340, 480), listOf(width, height, densityDpi))
                shown++
            }
            override fun hide(content: View) { assertSame(view, content); hidden++ }
        })
        try {
            drain.start()
            reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, android.os.Handler(drain.looper))
            presentation.setContentView(view, ViewGroup.LayoutParams(1080, 2340))
            presentation.show()
            presentation.show()
            assertEquals(1, shown)
            assertTrue(presentation.isShowing)
            val parent = view.parent as ViewGroup
            assertSame(view, parent.getChildAt(0))
            parent.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View, child: View) = Unit
                override fun onChildViewRemoved(parent: View, child: View) {
                    assertNull(child.parent)
                    presentation.dismiss()
                    display.release()
                    reader.setOnImageAvailableListener(null, null)
                    drain.quitSafely()
                    reader.close()
                }
            })
            parent.removeView(view)
            drain.join(2000)
            assertFalse(drain.isAlive)
            assertNull(view.parent)
            assertFalse(presentation.isShowing)
            assertFalse(display.display.isValid)
            assertFalse(surface.isValid)
            assertEquals(1, hidden)
            display.release()
            presentation.dismiss()
            assertEquals(1, hidden)
        } finally {
            presentation.dismiss()
            display.release()
            reader.close()
            drain.quitSafely()
            drain.join(2000)
            IosBrowserPresentation.setHost(null)
        }
    }

    @Test
    fun `display release unmounts a shown presentation and a failed native mount does not retain its child`() {
        val context = context()
        val reader = ImageReader.newInstance(390, 844, 1, 2)
        val display = DisplayManager().createVirtualDisplay("browser", 390, 844, 160, reader.surface, 10)
        val presentation = Presentation(context, display.display)
        val view = AbsoluteLayout(context)
        IosBrowserPresentation.setHost(null)
        try {
            presentation.setContentView(view, ViewGroup.LayoutParams(390, 844))
            assertThrows(UnsupportedOperationException::class.java) { presentation.show() }
            assertNull(view.parent)
            var hidden = 0
            IosBrowserPresentation.setHost(object : IosBrowserPresentation.Host {
                override fun show(view: View, width: Int, height: Int, densityDpi: Int) = Unit
                override fun hide(view: View) { hidden++ }
            })
            presentation.show()
            assertNotNull(view.parent)
            display.release()
            assertNull(view.parent)
            assertFalse(presentation.isShowing)
            assertEquals(1, hidden)
            assertThrows(IllegalStateException::class.java) { presentation.show() }
            assertNull(view.parent)
        } finally {
            display.release()
            reader.close()
            IosBrowserPresentation.setHost(null)
        }
    }
}
