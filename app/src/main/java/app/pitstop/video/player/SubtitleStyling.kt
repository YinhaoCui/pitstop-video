package app.pitstop.video.player

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import app.pitstop.video.core.SubtitleStyle

/** Applies the user's subtitle settings to a player's subtitle view (phone and car). */
@OptIn(UnstableApi::class)
object SubtitleStyling {
    fun apply(view: SubtitleView?, style: SubtitleStyle, scale: Float = 1f) {
        view ?: return
        view.setApplyEmbeddedStyles(false)
        view.setApplyEmbeddedFontSizes(false)
        view.setStyle(
            CaptionStyleCompat(
                style.foregroundArgb,
                style.backgroundArgb,
                Color.TRANSPARENT,
                when (style.edge) {
                    SubtitleStyle.Edge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
                    SubtitleStyle.Edge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                    SubtitleStyle.Edge.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                },
                Color.BLACK,
                null,
            )
        )
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale * style.sizePercent / 100f)
    }
}
