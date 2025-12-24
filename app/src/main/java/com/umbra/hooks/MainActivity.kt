package com.umbra.hooks

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.umbra.hooks.databinding.ActivityMainBinding

class MainActivity : Activity() {
    
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Navigate to Gboard Settings
        binding.cardGboard.setOnClickListener {
            startActivity(Intent(this, GboardActivity::class.java))
        }
    }
}