package app.lararium.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutLogicTest {
    @Test fun belowThresholdIsPhone() {
        assertEquals(LayoutMode.PHONE, LayoutLogic.modeForWidth(599.9f))
        assertEquals(LayoutMode.PHONE, LayoutLogic.modeForWidth(360f))
    }

    @Test fun thresholdAndAboveIsTablet() {
        assertEquals(LayoutMode.TABLET, LayoutLogic.modeForWidth(600f))
        assertEquals(LayoutMode.TABLET, LayoutLogic.modeForWidth(1280f))
        assertEquals(LayoutMode.TABLET, LayoutLogic.modeForWidth(Float.POSITIVE_INFINITY))
    }

    @Test fun degenerateWidthsArePhone() {
        assertEquals(LayoutMode.PHONE, LayoutLogic.modeForWidth(0f))
        assertEquals(LayoutMode.PHONE, LayoutLogic.modeForWidth(Float.NaN))
        assertEquals(LayoutMode.PHONE, LayoutLogic.modeForWidth(-1f))
        assertEquals(LayoutMode.PHONE, LayoutLogic.modeForWidth(Float.NEGATIVE_INFINITY))
    }

    @Test fun orientationChangeModeledAsWidthSwap() {
        // 10" tablet: ~800dp portrait <-> ~1280dp landscape; both tablet.
        assertEquals(LayoutMode.TABLET, LayoutLogic.modeForWidth(800f))
        assertEquals(LayoutMode.TABLET, LayoutLogic.modeForWidth(1280f))
        // Phone: 360 portrait <-> 780 landscape flips to tablet.
        assertEquals(LayoutMode.PHONE, LayoutLogic.modeForWidth(360f))
        assertEquals(LayoutMode.TABLET, LayoutLogic.modeForWidth(780f))
        assertEquals(LayoutMode.PHONE, LayoutLogic.modeForWidth(360f))
    }

    @Test fun chatLayoutDefaultsAndCapping() {
        val tablet = LayoutLogic.chatLayoutForWidth(900f)
        assertEquals(80f, tablet.railWidthDp, 0f)
        assertEquals(840f, tablet.contentMaxWidthDp, 0f)
        assertTrue(tablet.capContentWidth)
        assertTrue(tablet.useNavigationRail)

        val phone = LayoutLogic.chatLayoutForWidth(400f)
        assertFalse(phone.capContentWidth)
        assertFalse(phone.useNavigationRail)
    }
}
