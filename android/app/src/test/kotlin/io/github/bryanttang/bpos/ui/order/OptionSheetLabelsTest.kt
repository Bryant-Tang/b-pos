// 測試方法名一律用 ASCII（可以有空白、包在反引號裡），中文說明寫在註解。
// 原因見 sync/OutboxRepositoryTest.kt 開頭。
package io.github.bryanttang.bpos.ui.order

import io.github.bryanttang.bpos.menu.MenuFixtures
import io.github.bryanttang.bpos.menu.OptionGroupType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * bottom sheet 上那兩行提示文字。
 *
 * 這兩個是純函式，所以不用跑起 Compose 就測得到——店員看到的規則說明
 * 跟實際擋下來的規則對不起來的話，他會以為是系統壞了。
 */
class OptionSheetLabelsTest {

    // 必選剛好 N 個的組（辣度：min = max = 1）要講「必選」，不要講「至少…最多…」。
    @Test
    fun `a group with a fixed count reads as required`() {
        assertEquals("必選 1 個", requirementLabel(MenuFixtures.spiceGroup))
    }

    // 可以不選的複選組講「最多 N 個」。
    @Test
    fun `an optional group reads as a maximum`() {
        assertEquals("最多 2 個", requirementLabel(MenuFixtures.toppingGroup))
    }

    // 下限與上限不同的組要兩個數字都講出來。
    @Test
    fun `a group with a range reads as both bounds`() {
        val group = MenuFixtures.toppingGroup.copy(min = 1, max = 2)

        assertEquals("至少 1 個，最多 2 個", requirementLabel(group))
    }

    // 可選可不選的單選組講「可選 1 個」，而不是「最多 1 個」。
    @Test
    fun `an optional single choice group reads as pick one`() {
        val group = MenuFixtures.spiceGroup.copy(min = 0, max = 1, type = OptionGroupType.SINGLE)

        assertEquals("可選 1 個", requirementLabel(group))
    }

    // 價差正負都要看得出來方向。
    @Test
    fun `a price delta shows its sign`() {
        assertEquals("+$20", priceDeltaLabel(20))
        assertEquals("-$10", priceDeltaLabel(-10))
    }
}
