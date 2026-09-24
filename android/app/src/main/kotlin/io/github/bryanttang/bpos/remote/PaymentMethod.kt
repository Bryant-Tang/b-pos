package io.github.bryanttang.bpos.remote

/**
 * 結帳時客人怎麼付（closeOrderInput.ts 的 `payment.method`）。
 *
 * 只記錄方式，不串金流（SPEC 第一節）：刷卡與行動支付在店裡的機器上刷完，
 * 平板這邊按的是「這張單用這個方式收過了」。
 */
enum class PaymentMethod(val wireName: String) {
    CASH("cash"),
    MOBILE("mobile"),
    CARD("card"),
}
