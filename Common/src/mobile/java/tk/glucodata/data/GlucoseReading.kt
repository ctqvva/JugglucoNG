package tk.glucodata.data

data class GlucoseReading(
    val value: Float,
    val time: Long, // Epoch seconds or milliseconds? Natives usually use seconds for storage? Let's check. 
    // item.time is long. standard android is ms. 
    val type: Int = 0 // 0: Stream/History, 1: Scan, etc.
)