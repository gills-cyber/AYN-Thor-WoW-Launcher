package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemMenuHelperTest {
    @Test
    fun interfaceVersionFromBuildVersion() {
        assertEquals(120001, SystemMenuHelper.interfaceVersion("12.0.1.65432"))
        assertEquals(110207, SystemMenuHelper.interfaceVersion("11.2.7.60000"))
        assertEquals(11507, SystemMenuHelper.interfaceVersion("1.15.7.59000"))
        assertEquals(120000, SystemMenuHelper.interfaceVersion(" 12.0.0 "))
    }

    @Test
    fun interfaceVersionRejectsWhatItCannotRead() {
        assertNull(SystemMenuHelper.interfaceVersion(null))
        assertNull(SystemMenuHelper.interfaceVersion(""))
        assertNull(SystemMenuHelper.interfaceVersion("12.0"))
        assertNull(SystemMenuHelper.interfaceVersion("12.x.1.1"))
        assertNull(SystemMenuHelper.interfaceVersion("12.100.1.1"))
    }

    @Test
    fun interfaceListNamesTheInstalledVersionFirstAndItsNeighbours() {
        val list = SystemMenuHelper.interfaceList(120001).split(", ")
        assertEquals("120001", list.first())
        assertEquals(list.size, list.toSet().size)
        assertEquals(true, list.containsAll(listOf("120000", "120002", "120100")))
    }
}
