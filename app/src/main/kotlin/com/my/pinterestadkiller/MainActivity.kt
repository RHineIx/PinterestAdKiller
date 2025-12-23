package com.my.pinterestadkiller

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val textView = TextView(this)
        textView.text = "Pinterest AdKiller Active"
        textView.textSize = 20f
        textView.gravity = Gravity.CENTER
        
        setContentView(textView)
    }
}
