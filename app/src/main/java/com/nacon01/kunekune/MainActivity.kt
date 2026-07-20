package com.nacon01.kunekune

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this).apply {
            text = "Kunekune Escape Phase 0"
            gravity = Gravity.CENTER
        }
        setContentView(textView)
    }
}
