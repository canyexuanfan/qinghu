package top.hnwen17.guard.ui
import top.hnwen17.guard.R
/** Artwork fixtures excluded from the standard variant. */
object FlavorIcons {
    fun resource(key: String): Int = when(key) {
        "bag" -> R.drawable.preview_icon_bag
        "shortvideo" -> R.drawable.preview_icon_shortvideo
        "news" -> R.drawable.preview_icon_news
        "chat" -> R.drawable.preview_icon_chat
        "wallet" -> R.drawable.preview_icon_wallet
        "play" -> R.drawable.preview_icon_play
        "music" -> R.drawable.preview_icon_music
        "map" -> R.drawable.preview_icon_map
        "mail" -> R.drawable.preview_icon_mail
        "globe" -> R.drawable.preview_icon_globe
        "book" -> R.drawable.preview_icon_book
        else -> 0
    }
}
