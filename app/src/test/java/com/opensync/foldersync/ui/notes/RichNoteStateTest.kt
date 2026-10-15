package com.opensync.foldersync.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichNoteStateTest {

    @Test fun `a caret on or just after a box finds that checklist line`() {
        val s = RichNoteState("x\n- [ ] milk\n- [x] eggs\n- bread")
        // "x\n☐ milk\n☑ eggs\n• bread": the milk line starts at 2, eggs at 9.
        assertEquals(2, s.checkboxLineAt(2))
        assertEquals(2, s.checkboxLineAt(3))
        assertNull(s.checkboxLineAt(4))       // past the box, in the item's words
        assertEquals(9, s.checkboxLineAt(10))
        assertNull(s.checkboxLineAt(0))       // plain line
        assertNull(s.checkboxLineAt(17))      // a bullet isn't a box
    }

    @Test fun `tapping a box ticks it, can be undone, and leaves the caret at the item's end`() {
        val s = RichNoteState("x\n- [ ] milk\n- [x] eggs")
        s.tapCheckbox(2)
        assertEquals("x\n- [x] milk\n- [x] eggs", s.markdown)
        assertEquals(8, s.selection.start)
        s.tapCheckbox(9)
        assertEquals("x\n- [x] milk\n- [ ] eggs", s.markdown)
        assertTrue(s.canUndo)
        s.undo()
        assertEquals("x\n- [x] milk\n- [x] eggs", s.markdown)
    }
}
