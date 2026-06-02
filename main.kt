package com.appnajeet.user.ui.home

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.appnajeet.user.BaseActivity
import com.appnajeet.user.R
import com.appnajeet.user.ui.leaderboard.LeaderboardFragment
import com.appnajeet.user.ui.profile.ProfileFragment
import com.appnajeet.user.ui.transactions.TransactionsFragment
import com.appnajeet.user.utils.DisplayUtils
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.appnajeet.user.ads.RewardedAdManager
import com.appnajeet.user.ui.common.AnnouncementDialog

import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat

class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔥 CRITICAL FIX #1: Force set navigation bar color to match bottom nav
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            window.navigationBarColor = ContextCompat.getColor(this, R.color.background_secondary)
            window.statusBarColor = ContextCompat.getColor(this, R.color.background_primary)
        }

        // 🔥 CRITICAL FIX #2: System bars ke saath properly handle
        WindowCompat.setDecorFitsSystemWindows(window, true)

        setContentView(R.layout.activity_main)

        // 🆕 Purane users ke liye gems node auto-create
        initGemsNodeIfMissing()

        // 🔥 FIX NEW USER: App start hote hi ad preload karo
        if (!RewardedAdManager.isAdReady() && !RewardedAdManager.isLoading()) {
            RewardedAdManager.load(this)
        }

        // 🆕 Announcement popup — Firebase se
        AnnouncementDialog.checkAndShow(this)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation_view)
        val fab = findViewById<FloatingActionButton>(R.id.fab)
        val fragmentContainer = findViewById<View>(R.id.fragment_container)

        // 🔥 Apply window insets for notch/gesture navigation
        applyWindowInsets(fragmentContainer, bottomNav)

        // Optional: Debug bottom nav (remove after testing)
        // BottomNavDebug.debugBottomNav(bottomNav)

        // ✅ Default fragment
        if (savedInstanceState == null) {
            loadFragment(HomeFragment())
            bottomNav.selectedItemId = R.id.nav_home
        }

        // ✅ Bottom Navigation click
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home         -> { loadFragment(HomeFragment());        true }
                R.id.nav_leaderboard  -> { loadFragment(LeaderboardFragment()); true }
                R.id.nav_transactions -> { loadFragment(TransactionsFragment()); true }
                R.id.nav_profile      -> { loadFragment(ProfileFragment());     true }
                else -> false
            }
        }

        // ✅ FAB (ADS) - Center
        fab.setOnClickListener {
            loadFragment(AdsFragment())
        }
    }

    private fun initGemsNodeIfMissing() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val gemsRef = FirebaseDatabase.getInstance()
            .getReference("users")
            .child(uid)
            .child("gems")

        gemsRef.get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) {
                    gemsRef.setValue(0)
                        .addOnSuccessListener {
                            Log.d("MainActivity", "✅ gems:0 created for uid=$uid")
                        }
                        .addOnFailureListener { e ->
                            Log.e("MainActivity", "❌ gems init failed: ${e.message}")
                        }
                } else {
                    Log.d("MainActivity", "✅ gems already exists: ${snapshot.value}")
                }
            }
            .addOnFailureListener { e ->
                Log.e("MainActivity", "❌ gems check failed: ${e.message}")
            }
    }

    private fun applyWindowInsets(
        fragmentContainer: View,
        bottomNav: BottomNavigationView
    ) {
        DisplayUtils.applyWindowInsets(fragmentContainer) { left: Int, top: Int, right: Int, bottom: Int ->
            fragmentContainer.setPadding(left, top, right, 0)
        }

        DisplayUtils.applyWindowInsets(bottomNav) { left: Int, top: Int, right: Int, bottom: Int ->
            bottomNav.setPadding(left, 0, right, 0)
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}
