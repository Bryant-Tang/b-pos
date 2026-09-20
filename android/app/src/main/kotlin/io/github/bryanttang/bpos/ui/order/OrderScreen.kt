package io.github.bryanttang.bpos.ui.order

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
// LazyColumn 與 LazyVerticalGrid 各有一個叫 items 的擴充，簡名一樣會撞在一起，
// 所以把格狀那個取別名。不取別名的話編譯器會挑到 Int 那個多載，錯誤訊息完全看不出原因。
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bryanttang.bpos.menu.Menu
import io.github.bryanttang.bpos.menu.MenuCategory
import io.github.bryanttang.bpos.menu.MenuItem
import io.github.bryanttang.bpos.menu.priceFor
import io.github.bryanttang.bpos.order.Cart
import io.github.bryanttang.bpos.order.CartLine
import io.github.bryanttang.bpos.sync.OrderType
import io.github.bryanttang.bpos.ui.theme.BPosTheme

/**
 * 點餐畫面：左側分類、中間品項、右側購物車（SPEC 第六節〈畫面〉）。
 *
 * 這個 composable 自己**不持有狀態**，分類的選擇與購物車都由呼叫端傳進來。
 * 點餐途中平板可能被轉去看別桌再轉回來，狀態放在畫面裡的話那一車就沒了。
 */
@Composable
fun OrderScreen(
    menu: Menu,
    selectedCategoryId: String?,
    cart: Cart,
    orderType: OrderType,
    onCategorySelect: (String) -> Unit,
    onItemClick: (MenuItem) -> Unit,
    onQtyChange: (index: Int, qty: Int) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val categories = menu.categories.sortedBy { it.sort }
    val currentCategoryId = selectedCategoryId ?: categories.firstOrNull()?.categoryId

    Row(modifier = modifier.fillMaxSize()) {
        CategoryRail(
            categories = categories,
            selectedCategoryId = currentCategoryId,
            onCategorySelect = onCategorySelect,
            modifier = Modifier.width(CATEGORY_RAIL_WIDTH).fillMaxHeight(),
        )

        ItemGrid(
            items = currentCategoryId?.let { menu.availableItemsIn(it) }.orEmpty(),
            orderType = orderType,
            onItemClick = onItemClick,
            modifier = Modifier.weight(1f).fillMaxHeight().padding(GRID_PADDING),
        )

        CartPanel(
            menu = menu,
            cart = cart,
            orderType = orderType,
            onQtyChange = onQtyChange,
            onSubmit = onSubmit,
            modifier = Modifier.width(CART_WIDTH).fillMaxHeight(),
        )
    }
}

@Composable
private fun CategoryRail(
    categories: List<MenuCategory>,
    selectedCategoryId: String?,
    onCategorySelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        items(categories, key = { it.categoryId }) { category ->
            val selected = category.categoryId == selectedCategoryId
            Text(
                text = category.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onCategorySelect(category.categoryId) }
                    .padding(horizontal = 16.dp, vertical = 18.dp),
            )
        }
    }
}

/**
 * 品項格狀清單。
 *
 * 每格最小寬度固定，格數隨螢幕寬度自己長——平板橫放直放、10 吋 12 吋都要能用，
 * 寫死欄數的話換一台就會擠成一團。
 */
@Composable
private fun ItemGrid(
    items: List<MenuItem>,
    orderType: OrderType,
    onItemClick: (MenuItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = ITEM_MIN_WIDTH),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
        verticalArrangement = Arrangement.spacedBy(GRID_GAP),
    ) {
        gridItems(items, key = { it.itemId }) { item ->
            ItemCard(item = item, orderType = orderType, onClick = { onItemClick(item) })
        }
    }
}

@Composable
private fun ItemCard(
    item: MenuItem,
    orderType: OrderType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = item.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        // 外帶單要顯示外帶價。整張單都是外帶了，格子上還寫內用價的話，
        // 店員報給客人的金額會跟收據對不起來。
        Text(
            text = "$${item.priceFor(orderType)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun CartPanel(
    menu: Menu,
    cart: Cart,
    orderType: OrderType,
    onQtyChange: (index: Int, qty: Int) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        Text(
            text = "本單（共 ${cart.totalQty} 份）",
            style = MaterialTheme.typography.titleMedium,
        )

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(cart.lines) { index, line ->
                CartRow(
                    line = line,
                    onQtyChange = { qty -> onQtyChange(index, qty) },
                )
            }
        }

        HorizontalDivider()

        // 這個數字是拿平板手上的菜單快照算的，實際金額由伺服器重算。
        // 標題直接寫「預估」而不是「總計」，是為了不讓店員把它當成收據上的數字。
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "預估金額", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "$${cart.estimatedTotal(menu, orderType)}",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Button(
            onClick = onSubmit,
            enabled = !cart.isEmpty,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("送出")
        }
    }
}

@Composable
private fun CartRow(line: CartLine, onQtyChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = line.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )

        // 減號與加號各自把完整的目標數量算好再往外傳，讓購物車那邊只需要處理
        // 「把這一列設成 N」一種操作。減到 0 等於把那一列拿掉，由購物車負責。
        TextButton(
            onClick = { onQtyChange(line.qty - 1) },
            modifier = Modifier.semantics { contentDescription = "${line.name} 減一份" },
        ) {
            Text("−")
        }

        Box(modifier = Modifier.width(QTY_WIDTH), contentAlignment = Alignment.Center) {
            Text(text = line.qty.toString(), style = MaterialTheme.typography.titleMedium)
        }

        TextButton(
            onClick = { onQtyChange(line.qty + 1) },
            modifier = Modifier.semantics { contentDescription = "${line.name} 加一份" },
        ) {
            Text("＋")
        }
    }
}

private val CATEGORY_RAIL_WIDTH: Dp = 160.dp
private val CART_WIDTH: Dp = 280.dp
private val ITEM_MIN_WIDTH: Dp = 140.dp
private val GRID_PADDING: Dp = 16.dp
private val GRID_GAP: Dp = 12.dp
private val QTY_WIDTH: Dp = 32.dp

/*
 * 以下是給 Android Studio 看的預覽。
 *
 * 尺寸用 1280×800，就是店裡那台平板橫放的樣子。點餐畫面是三欄的，
 * 用手機尺寸預覽等於看不到它真正的問題（分類欄擠掉品項格）。
 *
 * 裡面的品項、價格、桌號全部是虛構的（CLAUDE.md 第一節），要改請繼續用假資料。
 */

private val previewMenu = Menu(
    version = 1,
    categories = listOf(
        MenuCategory(categoryId = "cat_noodle", name = "麵食", sort = 1),
        MenuCategory(categoryId = "cat_rice", name = "飯類", sort = 2),
        MenuCategory(categoryId = "cat_drink", name = "飲料", sort = 3),
    ),
    items = listOf(
        MenuItem(
            itemId = "item_beef_noodle",
            name = "牛肉麵",
            price = 180,
            takeoutPrice = 170,
            categoryId = "cat_noodle",
            sort = 1,
        ),
        MenuItem(itemId = "item_pork_noodle", name = "排骨麵", price = 150, categoryId = "cat_noodle", sort = 2),
        MenuItem(itemId = "item_wonton", name = "餛飩麵", price = 140, categoryId = "cat_noodle", sort = 3),
        MenuItem(itemId = "item_sold_out", name = "今日售完的麵", price = 999, categoryId = "cat_noodle", available = false, sort = 4),
        MenuItem(itemId = "item_bubble_tea", name = "珍珠奶茶", price = 60, categoryId = "cat_drink", sort = 1),
    ),
    optionGroups = emptyList(),
)

private val previewCart = Cart(
    lines = listOf(
        CartLine(itemId = "item_beef_noodle", name = "牛肉麵", selection = emptyMap(), qty = 2),
        CartLine(itemId = "item_bubble_tea", name = "珍珠奶茶", selection = emptyMap(), qty = 1),
    ),
)

@Preview(name = "點餐（平板橫放）", showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
private fun OrderScreenPreview() {
    BPosTheme {
        OrderScreen(
            menu = previewMenu,
            selectedCategoryId = "cat_noodle",
            cart = previewCart,
            orderType = OrderType.DINE_IN,
            onCategorySelect = {},
            onItemClick = {},
            onQtyChange = { _, _ -> },
            onSubmit = {},
        )
    }
}

/**
 * 空車的樣子：送出鈕要是鎖住的，預估金額是 0。
 * 這個狀態是開新單時的第一眼，值得單獨看一次。
 */
@Preview(name = "點餐（空車）", showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
private fun OrderScreenEmptyPreview() {
    BPosTheme {
        OrderScreen(
            menu = previewMenu,
            selectedCategoryId = null,
            cart = Cart(),
            orderType = OrderType.DINE_IN,
            onCategorySelect = {},
            onItemClick = {},
            onQtyChange = { _, _ -> },
            onSubmit = {},
        )
    }
}

/** 外帶單：格子上的價格要換成外帶價（牛肉麵 170），沒設外帶價的照內用價。 */
@Preview(name = "點餐（外帶價）", showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
private fun OrderScreenTakeoutPreview() {
    BPosTheme {
        OrderScreen(
            menu = previewMenu,
            selectedCategoryId = "cat_noodle",
            cart = previewCart,
            orderType = OrderType.TAKEOUT,
            onCategorySelect = {},
            onItemClick = {},
            onQtyChange = { _, _ -> },
            onSubmit = {},
        )
    }
}
