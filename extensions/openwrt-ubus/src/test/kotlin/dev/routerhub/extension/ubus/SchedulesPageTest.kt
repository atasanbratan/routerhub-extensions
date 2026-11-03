package dev.routerhub.extension.ubus

import dev.routerhub.api.SettingField
import dev.routerhub.api.TableCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulesPageTest {
    private val rpcd = FakeRpcd()
    private val mac = "02:00:00:00:aa:01"

    private fun save(vararg rows: List<String>) =
        SchedulesPage.applyChanges(rpcd, SchedulesPage.SCHEDULES to TableCodec.encode(rows.toList()))

    private fun schedules() = rpcd.sections("firewall", "rule").filter { it["name"].toString().startsWith("RouterHub schedule") }

    @Test
    fun `a night-time schedule becomes a rule with times and weekdays`() {
        save(listOf("Bedtime", mac, "22:00", "7:00", "Mon Tue Wed Thu Fri"))
        val rule = schedules().single()
        assertEquals("RouterHub schedule: Bedtime", rule["name"])
        assertEquals(listOf("lan", "wan", mac, "REJECT"), listOf("src", "dest", "src_mac", "target").map { rule[it] })
        assertEquals(listOf("22:00:00", "07:00:00", "Mon Tue Wed Thu Fri"), listOf("start_time", "stop_time", "weekdays").map { rule[it] })
        assertEquals(listOf(listOf("Bedtime", mac, "22:00", "07:00", "Mon Tue Wed Thu Fri")), SchedulesPage.readPage(rpcd).rows(SchedulesPage.SCHEDULES))
    }

    @Test
    fun `every day leaves the weekdays out, and switching to it drops them`() {
        save(listOf("", mac, "21:30", "23:00", "Sat Sun"))
        save(listOf("", mac, "21:30", "23:00", "every"))
        val rule = schedules().single()
        assertFalse("weekdays" in rule)
        assertEquals("RouterHub schedule", rule["name"])
    }

    @Test
    fun `bad times are refused before anything is sent`() {
        assertTrue(rejected(SchedulesPage.SCHEDULES) { save(listOf("", mac, "24:00", "07:00", "every")) }.contains("like 22:00"))
        assertTrue(rejected(SchedulesPage.SCHEDULES) { save(listOf("", mac, "22:00", "22:00", "every")) }.contains("same time"))
        assertTrue(schedules().isEmpty())
    }

    @Test
    fun `days set elsewhere stay as they are and can be chosen`() {
        save(listOf("", mac, "08:00", "15:00", "Mon Wed"))
        val page = SchedulesPage.readPage(rpcd)
        val days = (page.field(SchedulesPage.SCHEDULES) as SettingField.Table).columns.last().options.map { it.value }
        assertTrue("Mon Wed" in days)
        assertEquals("Mon Wed", page.rows(SchedulesPage.SCHEDULES).single()[4])
    }

    @Test
    fun `blocks and other rules are not schedules`() {
        val before = rpcd.sections("firewall", "rule").size
        save()
        assertEquals(before, rpcd.sections("firewall", "rule").size)
        assertTrue(SchedulesPage.readPage(rpcd).rows(SchedulesPage.SCHEDULES).isEmpty())
    }
}
