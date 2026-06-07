package helium314.keyboard.latin.voice

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import helium314.keyboard.latin.R

class TranscriptPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val textView: TextView
    val reformatButton: MaterialButton
    val insertButton: MaterialButton

    init {
        LayoutInflater.from(context).inflate(R.layout.voice_transcript_preview, this, true)
        orientation = VERTICAL
        textView = findViewById(R.id.voice_preview_text)
        reformatButton = findViewById(R.id.voice_btn_reformat)
        insertButton = findViewById(R.id.voice_btn_insert)
    }

    fun setText(text: String) {
        textView.text = text
    }

    fun text(): String = textView.text.toString()

    fun setReformatVisible(visible: Boolean) {
        reformatButton.visibility = if (visible) VISIBLE else GONE
    }

    fun setOnInsertClick(listener: (String) -> Unit) {
        insertButton.setOnClickListener { listener(text()) }
    }

    fun setOnReformatClick(listener: (String) -> Unit) {
        reformatButton.setOnClickListener { listener(text()) }
    }
}
