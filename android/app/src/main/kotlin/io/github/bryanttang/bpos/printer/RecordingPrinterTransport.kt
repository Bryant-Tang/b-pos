package io.github.bryanttang.bpos.printer

/**
 * 不連任何硬體、只把送出的內容留下來的印表機。
 *
 * 開發初期沒有實體機器，但「訂單 → 排版 → 點陣圖 → 位元組」這一整條要能先做完做對
 * （SPEC 第七節〈沒有真機時的開發方式〉）。把位元組留著，設定頁就能把最近幾張單
 * 還原成圖顯示出來，排版對不對用眼睛就看得出來，不必等硬體到貨。
 *
 * 它也是現場除錯的一條退路：印表機壞掉的那天，把 transport 切到這裡，
 * 至少還看得到單子長什麼樣、內容對不對。
 *
 * 契約跟真的連線一樣嚴：沒 [connect] 就 [write] 一樣失敗。假的比真的寬鬆的話，
 * 拿它測出來的「會動」到現場就不算數了。
 */
class RecordingPrinterTransport(
    private val keep: Int = DEFAULT_KEEP,
) : PrinterTransport {

    init {
        require(keep > 0) { "至少要留一張，收到 $keep" }
    }

    private val jobs = ArrayDeque<ByteArray>()
    private var connected = false

    /** 最近送出的內容，舊的在前。最多 [keep] 筆。 */
    val recorded: List<ByteArray>
        get() = synchronized(jobs) { jobs.toList() }

    override suspend fun connect(): Result<Unit> {
        connected = true
        return Result.success(Unit)
    }

    override suspend fun write(bytes: ByteArray): Result<Unit> {
        if (!connected) return Result.failure(IllegalStateException("還沒連上印表機（錄製用）"))
        synchronized(jobs) {
            // 複製一份再存：呼叫端如果重複使用同一個 buffer，存進去的就會跟著被改掉，
            // 預覽畫面上看到的就不是當初印出去的那張了。
            jobs.addLast(bytes.copyOf())
            while (jobs.size > keep) jobs.removeFirst()
        }
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        connected = false
    }

    companion object {
        /** 設定頁要能「檢視最近 10 張列印預覽」（SPEC 第七節）。 */
        const val DEFAULT_KEEP = 10
    }
}
