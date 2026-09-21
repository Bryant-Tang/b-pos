package io.github.bryanttang.bpos.tables

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 *
 * 資料全部是虛構的（CLAUDE.md 第一條）：桌名用「窗邊」「包廂一」，區域用「一樓」。
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TableMappingTest {

    private fun doc(vararg over: Pair<String, Any?>): Map<String, Any?> = mapOf(
        "areaId" to "area_1f",
        "label" to "窗邊",
        "sort" to 3L,
        "seats" to 4L,
        "x" to 0.2,
        "y" to 0.3,
        "shape" to "square",
        "qrToken" to "0123456789abcdef0123456789abcdef",
        "archived" to false,
    ) + over

    @Test
    fun `a normal table maps across`() {
        val table = toTable("table_1", doc())

        assertNotNull(table)
        assertEquals("table_1", table!!.tableId)
        assertEquals("窗邊", table.label)
        assertEquals("area_1f", table.zoneId)
        assertEquals(3, table.sort)
        assertEquals(4, table.seats)
        assertEquals(0.2f, table.x, 0.0001f)
        assertEquals(0.3f, table.y, 0.0001f)
    }

    // 桌名是任意字串（SPEC 第三節〈桌號命名〉），不可以假設它是數字或英數。
    @Test
    fun `any label is accepted`() {
        for (label in listOf("A3", "包廂一", "窗邊", "吧台3", "蘇東坡")) {
            assertEquals(label, toTable("t", doc("label" to label))?.label)
        }
    }

    @Test
    fun `an archived table is left off the floor plan`() {
        assertNull(toTable("table_1", doc("archived" to true)))
    }

    // 欄位缺了當成沒下架：漏顯示一張還在用的桌子，比多顯示一張已經收起來的麻煩得多。
    @Test
    fun `a missing archived flag means not archived`() {
        val data = doc().toMutableMap().apply { remove("archived") }
        assertNotNull(toTable("table_1", data))
    }

    @Test
    fun `a table without a label or id is skipped`() {
        assertNull(toTable("table_1", doc("label" to "")))
        assertNull(toTable("table_1", doc("label" to null)))
        assertNull(toTable("", doc()))
    }

    /**
     * 座標存壞了就夾回邊緣，不要丟掉那張桌。
     *
     * 夾住的結果是桌子貼在平面圖邊上，店員看得到也按得到；丟掉則是那張桌從此點不了餐，
     * 而且畫面上沒有任何線索說少了一張。
     */
    @Test
    fun `out of range coordinates are clamped, not fatal`() {
        val table = toTable("table_1", doc("x" to 1.5, "y" to -0.4))

        assertNotNull(table)
        assertEquals(1f, table!!.x, 0.0001f)
        assertEquals(0f, table.y, 0.0001f)
    }

    @Test
    fun `broken coordinates fall back to the corner`() {
        val table = toTable("table_1", doc("x" to "左邊", "y" to Double.NaN))

        assertNotNull(table)
        assertEquals(0f, table!!.x, 0.0001f)
        assertEquals(0f, table.y, 0.0001f)
    }

    // 後台存 x: 0 時 Firestore 回 Long，存 x: 0.25 時回 Double，兩種都要吃得下。
    @Test
    fun `coordinates arrive as Long or Double`() {
        val table = toTable("table_1", doc("x" to 0L, "y" to 1L))

        assertEquals(0f, table!!.x, 0.0001f)
        assertEquals(1f, table.y, 0.0001f)
    }

    @Test
    fun `missing numbers fall back to zero`() {
        val table = toTable("table_1", doc("sort" to null, "seats" to "四人"))

        assertEquals(0, table!!.sort)
        assertEquals(0, table.seats)
    }

    @Test
    fun `a table with no area still maps`() {
        assertEquals("", toTable("table_1", doc("areaId" to null))?.zoneId)
    }
}

class ZoneMappingTest {

    @Test
    fun `a normal area maps across`() {
        val zone = toZone("area_1f", mapOf("name" to "一樓", "sort" to 0L))

        assertEquals("area_1f", zone?.zoneId)
        assertEquals("一樓", zone?.name)
        assertEquals(0, zone?.sort)
    }

    @Test
    fun `an area without a name is skipped`() {
        assertNull(toZone("area_1f", mapOf("sort" to 0L)))
        assertNull(toZone("area_1f", mapOf("name" to "  ", "sort" to 0L)))
        assertNull(toZone("", mapOf("name" to "一樓")))
    }
}

class FloorPlanTest {

    private fun table(id: String, zoneId: String, sort: Int) =
        Table(tableId = id, label = id, zoneId = zoneId, x = 0f, y = 0f, sort = sort, seats = 2)

    /**
     * 分頁裡的桌子照 sort 排，絕不照 label 排（SPEC 第三節）。
     * 中文會變成按 Unicode 碼位排，而且 A10 會排在 A2 前面。
     */
    @Test
    fun `tables in a zone come back in sort order`() {
        val plan = FloorPlan(
            zones = listOf(Zone("area_1f", "一樓", 0)),
            tables = listOf(
                table("A10", "area_1f", 2),
                table("A2", "area_1f", 1),
                table("B1", "area_2f", 0),
            ),
        )

        assertEquals(listOf("A2", "A10"), plan.tablesIn("area_1f").map { it.tableId })
    }

    @Test
    fun `an empty zone has no tables`() {
        val plan = FloorPlan(zones = emptyList(), tables = listOf(table("A1", "area_1f", 0)))

        assertEquals(emptyList<Table>(), plan.tablesIn("area_2f"))
    }
}
