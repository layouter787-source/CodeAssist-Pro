package dev.layouter.codeassist.ndksample

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    private external fun nativeGreeting(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TextView(this).apply {
            text = nativeGreeting()
            textSize = 20f
            setPadding(32, 32, 32, 32)
            contentDescription = "Message returned by the native C++ library"
            setOnClickListener { text = nativeGreeting() }
        }.also(::setContentView)
    }
}
