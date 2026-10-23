package com.malt.photosync

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.malt.photosync.databinding.ActivityMainBinding
import com.malt.photosync.ui.AlbumsFragment
import com.malt.photosync.ui.HomeFragment
import com.malt.photosync.ui.SettingsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) show(HomeFragment())
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> show(HomeFragment())
                R.id.nav_settings -> show(SettingsFragment())
            }
            true
        }
    }

    private fun show(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, f)
            .commit()
    }

    /** 设置页进入相册选择（二级页） */
    fun openAlbums() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, AlbumsFragment())
            .addToBackStack(null)
            .commit()
    }
}
