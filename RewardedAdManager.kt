package com.appnajeet.user.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

object RewardedAdManager {

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false
    private var isShowing = false
    private var retryCount = 0
    private val MAX_RETRY = 3  // 🔥 Increased to 3 retries
    private var lastLoadTime = 0L
    private val CACHE_TIMEOUT = 60000L

    // 🔥 Error tracking
    private var lastErrorCode: Int? = null
    private var lastErrorMessage: String? = null
    private var onAdLoadedListener: (() -> Unit)? = null

    // 🔥 Listener for ad show state changes
    private var onAdShowStateChanged: ((isShowing: Boolean) -> Unit)? = null

    private const val AD_UNIT_ID = "ca-app-pub-9472852048158919/4145233250"
    private const val TAG = "RewardedAdManager"

    // 🔥 Error codes with user-friendly messages
    private val ERROR_MESSAGES = mapOf(
        0 to "Internal error, please try again",
        1 to "Invalid ad request",
        2 to "Network error, check connection",
        3 to "No ads available right now, please try again later",
        4 to "Ad request timed out",
        -1 to "Unknown error occurred"
    )

    fun load(context: Context, force: Boolean = false) {
        try {
            val now = System.currentTimeMillis()

            if (!force && rewardedAd != null && (now - lastLoadTime) < CACHE_TIMEOUT) {
                Log.d(TAG, "Using cached ad")
                onAdLoadedListener?.invoke()
                return
            }

            if (isLoading) {
                Log.d(TAG, "Already loading...")
                return
            }

            if (rewardedAd != null) {
                rewardedAd = null
            }

            isLoading = true
            retryCount = 0
            lastErrorCode = null
            lastErrorMessage = null
            performLoad(context)

        } catch (e: Exception) {
            Log.e(TAG, "Load error", e)
            isLoading = false
        }
    }

    private fun performLoad(context: Context) {
        Log.d(TAG, "Loading ad... Attempt ${retryCount + 1}")

        try {
            RewardedAd.load(
                context,
                AD_UNIT_ID,
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {

                    override fun onAdLoaded(ad: RewardedAd) {
                        rewardedAd = ad
                        isLoading = false
                        retryCount = 0
                        lastLoadTime = System.currentTimeMillis()
                        lastErrorCode = null
                        lastErrorMessage = null
                        Log.d(TAG, "✅ Ad Loaded Successfully")

                        Handler(Looper.getMainLooper()).post {
                            onAdLoadedListener?.invoke()
                        }
                    }

                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        rewardedAd = null
                        isLoading = false

                        val errorCode = loadAdError.code
                        val errorMessage = loadAdError.message ?: "Unknown error"

                        lastErrorCode = errorCode
                        lastErrorMessage = errorMessage

                        Log.e(TAG, "❌ Load failed: $errorMessage (Code: $errorCode)")

                        when (errorCode) {
                            3 -> { // No fill - common error
                                Log.d(TAG, "No ads available - will retry with backoff")
                                if (retryCount < MAX_RETRY) {
                                    retryCount++
                                    // 🔥 FIX: True exponential backoff: 2s, 4s, 8s
                                    val delay = (1L shl retryCount) * 1000L  // 2^retry * 1000ms
                                    Log.d(TAG, "Retry $retryCount in ${delay}ms")
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        performLoad(context)
                                    }, delay)
                                } else {
                                    Log.d(TAG, "Max retries reached for no fill")
                                }
                            }
                            2 -> { // Network error
                                Log.d(TAG, "Network error - will retry")
                                if (retryCount < MAX_RETRY) {
                                    retryCount++
                                    // 🔥 Network error: 3s fixed delay
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        performLoad(context)
                                    }, 3000)
                                }
                            }
                            else -> {
                                if (retryCount < 2) {
                                    retryCount++
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        performLoad(context)
                                    }, 3000)
                                }
                            }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Perform load error", e)
            isLoading = false
        }
    }

    fun show(
        activity: Activity,
        onReward: (rewardAmount: Int, rewardType: String) -> Unit,
        onClosed: () -> Unit,
        onFailed: (String) -> Unit = {}
    ) {
        try {
            if (isShowing) {
                onFailed("Ad already showing")
                return
            }

            val ad = rewardedAd
            if (ad == null) {
                if (!isLoading) {
                    load(activity, force = true)
                }
                val userMessage = getUserFriendlyError()
                onFailed(userMessage)
                return
            }

            isShowing = true
            // 🔥 Notify that ad is showing
            onAdShowStateChanged?.invoke(true)

            ad.fullScreenContentCallback = object : FullScreenContentCallback() {

                override fun onAdShowedFullScreenContent() {
                    Log.d(TAG, "✅ Ad showed fullscreen")
                }

                override fun onAdDismissedFullScreenContent() {
                    Log.d(TAG, "✅ Ad dismissed fullscreen")
                    rewardedAd = null
                    isShowing = false
                    onAdShowStateChanged?.invoke(false)
                    onClosed()
                    // 🔥 FIX: onClosed ke BAAD load karo — fragment state settle ho jaaye
                    Handler(Looper.getMainLooper()).postDelayed({
                        load(activity)
                    }, 300)
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    Log.e(TAG, "❌ Failed to show: ${adError.message}")
                    rewardedAd = null
                    isShowing = false
                    // 🔥 Notify that ad failed
                    onAdShowStateChanged?.invoke(false)
                    load(activity, force = true)
                    onFailed("Failed to show ad, try again")
                    onClosed()
                }
            }

            ad.show(activity) { rewardItem ->
                val rewardAmount = rewardItem.amount
                val rewardType = rewardItem.type
                Log.d(TAG, "🎁 Reward earned: $rewardAmount $rewardType")
                onReward(rewardAmount, rewardType)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Show error", e)
            isShowing = false
            onAdShowStateChanged?.invoke(false)
            onFailed("Something went wrong")
        }
    }

    // 🔥 Set listener for ad show state changes
    fun setOnAdShowStateChangedListener(listener: (isShowing: Boolean) -> Unit) {
        this.onAdShowStateChanged = listener
    }

    // 🔥 Get user-friendly error message
    fun getUserFriendlyError(): String {
        return when (lastErrorCode) {
            3 -> "No ads available right now, please try again later"
            2 -> "Network error, check your internet connection"
            1 -> "Invalid request"
            0 -> "Internal error, please try again"
            else -> "Ad not available, please try again"
        }
    }

    fun getLastError(): String? = lastErrorMessage
    fun isAdReady(): Boolean {
        if (rewardedAd == null || isShowing) return false
        // 🔥 FIX: 3 ghante se zyada purana ad stale ho jaata hai — fresh load karo
        val age = System.currentTimeMillis() - lastLoadTime
        if (age > 3 * 60 * 60 * 1000L) {  // 3 hours
            Log.d(TAG, "Ad stale (${age/1000}s old) — clearing")
            rewardedAd = null
            return false
        }
        return true
    }
    fun isLoading(): Boolean = isLoading
    fun isShowing(): Boolean = isShowing

    fun setOnAdLoadedListener(listener: () -> Unit) {
        this.onAdLoadedListener = listener
    }

    fun reset() {
        rewardedAd = null
        isLoading = false
        isShowing = false
        retryCount = 0
        lastErrorCode = null
        lastErrorMessage = null
        onAdLoadedListener = null
        onAdShowStateChanged = null
    }
}
