package top.hnwen17.guard.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import android.widget.ImageView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import top.hnwen17.guard.R
import top.hnwen17.guard.core.AppEntry

class AppIconLoader(context: Context) {
    private val app = context.applicationContext
    private val cache = object : LruCache<String, Bitmap>(1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val gates = Semaphore(2)
    fun bind(view: ImageView, entry: AppEntry, scope: CoroutineScope): Job? {
        view.tag = entry.id
        FlavorIcons.resource(entry.icon).takeIf { it != 0 }?.let { artwork ->
            view.imageTintList = null
            view.setBackgroundResource(0)
            view.setPadding(0, 0, 0, 0)
            view.setImageResource(artwork)
            return null
        }
        val id = when(entry.icon) {
            "bag" -> R.drawable.ic_bag; "music" -> R.drawable.ic_music; "records" -> R.drawable.ic_records
            "chat" -> R.drawable.ic_chat; "wallet" -> R.drawable.ic_wallet; "play" -> R.drawable.ic_play
            "map" -> R.drawable.ic_map; "mail" -> R.drawable.ic_mail; "globe" -> R.drawable.ic_globe
            "book" -> R.drawable.ic_book; else -> R.drawable.ic_apps
        }
        val pad = (view.resources.displayMetrics.density * 10).toInt()
        view.setPadding(pad, pad, pad, pad)
        view.setImageResource(id)
        val palette = when(entry.icon) {
            "bag", "play" -> R.color.orange to R.drawable.bg_orange
            "music" -> R.color.purple to R.drawable.bg_purple
            "chat", "book" -> R.color.green to R.drawable.bg_mint
            else -> R.color.blue to R.drawable.bg_blue
        }
        view.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(view.context, palette.first))
        view.setBackgroundResource(palette.second)
        if (entry.icon != "installed") return null
        cache.get(entry.id)?.let { view.imageTintList = null; view.setPadding(0,0,0,0); view.setImageBitmap(it); return null }
        return scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                gates.withPermit {
                    try {
                        val drawable = app.packageManager.getApplicationIcon(entry.id)
                        val size = (48 * app.resources.displayMetrics.density).toInt().coerceIn(48, 192)
                        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
                            drawable.setBounds(0, 0, size, size); drawable.draw(Canvas(it)); cache.put(entry.id, it)
                        }
                    } catch (e: Exception) { if(e is CancellationException) throw e; null }
                }
            }
            if (bitmap != null && view.tag == entry.id) {
                view.imageTintList = null; view.setPadding(0,0,0,0); view.setImageBitmap(bitmap)
            }
        }
    }
    fun clear() { cache.evictAll() }
}
