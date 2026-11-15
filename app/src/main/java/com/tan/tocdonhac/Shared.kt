package com.tan.tocdonhac

/** Trạng thái dùng chung giữa màn hình và dịch vụ phát nhạc (cùng tiến trình). */
object Shared {
    /** Thời điểm tắt nhạc (SystemClock.elapsedRealtime), 0 = không hẹn giờ */
    @Volatile var sleepEndAt: Long = 0L
    /** Tắt khi hết bài đang phát */
    @Volatile var sleepEndOfTrack: Boolean = false
    /** Giảm dần âm lượng trước khi tắt */
    @Volatile var fadeOut: Boolean = true
    /** Lặp đoạn A-B (ms), -1 = chưa đặt */
    @Volatile var abA: Long = -1L
    @Volatile var abB: Long = -1L

    const val FADE_MS = 20_000L
}
