package com.appnajeet.user.ui.home

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.appnajeet.user.R
import com.appnajeet.user.ads.RewardedAdManager
import com.appnajeet.user.databinding.FragmentAdsBinding
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.util.Calendar

class AdsFragment : Fragment() {

    private var _binding: FragmentAdsBinding? = null
    private val binding get() = _binding!!

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseDatabase.getInstance().reference }

    private val DAILY_AD_LIMIT = 5
    private var isAdShowing = false
    private var isLoadingAd = false
    private var wasAdReady = false
    private var isWaitingForAd = false
    private var retryCount = 0
    private val MAX_RETRY = 3
    private var rewardEarned = false  // 🔥 NEW

    private var coinsListener: ValueEventListener? = null
    private var adsListener: ValueEventListener? = null

    private val handler = Handler(Looper.getMainLooper())
    private var loadingTimeoutRunnable: Runnable? = null
    private var loadingTimerRunnable: Runnable? = null
    private var waitRunnable: Runnable? = null

    private val TAG = "AdsFragment"

    private var backPressedCallback: OnBackPressedCallback? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupAdListener()
        setupAdShowStateListener()
        resetLoadingState()
        loadCoins()
        loadDailyAdsCount()
        setupClickListeners()
        checkAdState()

        if (!RewardedAdManager.isAdReady() && !RewardedAdManager.isLoading()) {
            Log.d(TAG, "Fragment opened — forcing ad preload")
            RewardedAdManager.load(requireContext(), force = true)
        }

        setupBackPressHandler()
    }

    private fun setupAdListener() {
        RewardedAdManager.setOnAdLoadedListener {
            handler.post {
                if (!isAdded) return@post

                Log.d(TAG, "🔥 AD LOADED CALLBACK RECEIVED!")
                wasAdReady = true
                isLoadingAd = false
                retryCount = 0

                if (isWaitingForAd) {
                    Log.d(TAG, "Waiting for ad - showing now")
                    val uid = auth.currentUser?.uid ?: return@post
                    val today = todayKey()
                    showAdImmediately(uid, today)
                } else {
                    updateButtonToReady()
                }
            }
        }
    }

    private fun setupAdShowStateListener() {
        RewardedAdManager.setOnAdShowStateChangedListener { isShowing ->
            isAdShowing = isShowing
            Log.d(TAG, "Ad show state changed: isShowing = $isShowing")
        }
    }

    private fun setupBackPressHandler() {
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val adCurrentlyShowing = isAdShowing || RewardedAdManager.isShowing()

                Log.d(TAG, "Back pressed - States: isAdShowing=$isAdShowing, managerShowing=${RewardedAdManager.isShowing()}, isLoadingAd=$isLoadingAd, isWaitingForAd=$isWaitingForAd")

                when {
                    adCurrentlyShowing -> {
                        Log.d(TAG, "🚫 Back press BLOCKED - ad is showing")
                    }

                    isLoadingAd || isWaitingForAd -> {
                        Log.d(TAG, "🚫 Back press BLOCKED - ad is loading/waiting")
                        showToast("Please wait, ad is loading...")
                    }

                    else -> {
                        Log.d(TAG, "✅ Back press ALLOWED - cleaning up")
                        cleanupBeforeExit()
                        this.isEnabled = false
                        requireActivity().onBackPressed()
                    }
                }
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            backPressedCallback!!
        )
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - checking ad state")
        isAdShowing = RewardedAdManager.isShowing()
        checkAdState()
    }

    override fun onPause() {
        super.onPause()
        cancelWaitRunnable()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cleanupBeforeExit()
        RewardedAdManager.setOnAdLoadedListener {}
        RewardedAdManager.setOnAdShowStateChangedListener {}
        removeFirebaseListeners()
        resetState()

        backPressedCallback?.remove()
        backPressedCallback = null

        _binding = null
    }

    private fun removeFirebaseListeners() {
        val uid = auth.currentUser?.uid ?: return

        coinsListener?.let {
            db.child("users").child(uid).child("coins")
                .removeEventListener(it)
            coinsListener = null
        }

        adsListener?.let {
            db.child("user_ads").child(uid).child(todayKey())
                .removeEventListener(it)
            adsListener = null
        }
    }

    private fun resetState() {
        isAdShowing = false
        isLoadingAd = false
        wasAdReady = false
        isWaitingForAd = false
        retryCount = 0
        rewardEarned = false  // 🔥 NEW
    }

    private fun setupClickListeners() {
        binding.btnWatchAd.setOnClickListener { watchAd() }
        binding.btnWithdraw.setOnClickListener { checkUpiAndShowWithdrawDialog() }
    }

    private fun checkAdState() {
        try {
            when {
                RewardedAdManager.isAdReady() -> {
                    Log.d(TAG, "✅ Ad is ready")
                    wasAdReady = true
                    isLoadingAd = false
                    isWaitingForAd = false
                    retryCount = 0
                    updateButtonToReady()
                }
                RewardedAdManager.isLoading() -> {
                    Log.d(TAG, "⏳ Ad is loading")
                    isLoadingAd = true
                    wasAdReady = false
                    isWaitingForAd = false
                    updateButtonToLoading()
                }
                else -> {
                    Log.d(TAG, "🔄 No ad, loading...")
                    isLoadingAd = false
                    wasAdReady = false
                    isWaitingForAd = false
                    updateButtonToReady()
                    preloadAd()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Check ad state error", e)
            updateButtonToReady()
        }
    }

    private fun resetLoadingState() {
        cancelWaitRunnable()

        wasAdReady = if (RewardedAdManager.isAdReady()) {
            updateButtonToReady()
            true
        } else {
            updateButtonToReady()
            false
        }

        isLoadingAd = false
        isWaitingForAd = false
        retryCount = 0
    }

    private fun updateButtonToReady() {
        try {
            binding.btnWatchAd.isEnabled = true
            binding.btnWatchAd.text = "Watch Ad & Earn Coins"
            binding.progressBar.visibility = View.GONE
            Log.d(TAG, "Button READY")
        } catch (e: Exception) {
            Log.e(TAG, "UI update error", e)
        }
    }

    private fun updateButtonToLoading() {
        try {
            binding.btnWatchAd.isEnabled = false
            binding.btnWatchAd.text = "Loading Ad..."
            binding.progressBar.visibility = View.VISIBLE
            Log.d(TAG, "Button LOADING")
        } catch (e: Exception) {
            Log.e(TAG, "UI update error", e)
        }
    }

    private fun updateButtonToStarting() {
        try {
            binding.btnWatchAd.isEnabled = false
            binding.btnWatchAd.text = "Starting Ad..."
            binding.progressBar.visibility = View.VISIBLE
            Log.d(TAG, "Button STARTING")
        } catch (e: Exception) {
            Log.e(TAG, "UI update error", e)
        }
    }

    private fun cleanupBeforeExit() {
        loadingTimeoutRunnable?.let { handler.removeCallbacks(it) }
        loadingTimerRunnable?.let { handler.removeCallbacks(it) }
        cancelWaitRunnable()
        loadingTimeoutRunnable = null
        loadingTimerRunnable = null
    }

    private fun cancelWaitRunnable() {
        waitRunnable?.let { handler.removeCallbacks(it) }
        waitRunnable = null
        isWaitingForAd = false
    }

    private fun preloadAd() {
        try {
            if (isWaitingForAd) {
                Log.d(TAG, "Waiting for ad, skipping preload")
                return
            }

            if (RewardedAdManager.isAdReady()) {
                Log.d(TAG, "Ad already ready")
                isLoadingAd = false
                wasAdReady = true
                updateButtonToReady()
                return
            }

            if (RewardedAdManager.isLoading()) {
                Log.d(TAG, "Ad already loading")
                isLoadingAd = true
                updateButtonToLoading()
                return
            }

            Log.d(TAG, "Starting preload")
            isLoadingAd = true
            wasAdReady = false
            updateButtonToLoading()

            RewardedAdManager.load(requireContext())
            startAdReadyChecker()
            startLoadingTimeout()

        } catch (e: Exception) {
            Log.e(TAG, "Preload error", e)
            isLoadingAd = false
            updateButtonToReady()
        }
    }

    private fun startLoadingTimeout() {
        loadingTimeoutRunnable = Runnable {
            if (isLoadingAd && !RewardedAdManager.isAdReady() && !isWaitingForAd) {
                Log.d(TAG, "Ad loading timeout")
                isLoadingAd = false
                wasAdReady = false

                if (retryCount < MAX_RETRY) {
                    retryCount++
                    Log.d(TAG, "Retrying preload (attempt $retryCount/$MAX_RETRY)")
                    preloadAd()
                } else {
                    updateButtonToReady()
                    showToast("Failed to load ad after $MAX_RETRY attempts")
                }
            }
        }
        handler.postDelayed(loadingTimeoutRunnable!!, 8000)
    }

    private fun startAdReadyChecker() {
        loadingTimerRunnable?.let { handler.removeCallbacks(it) }

        loadingTimerRunnable = object : Runnable {
            override fun run() {
                try {
                    if (RewardedAdManager.isAdReady()) {
                        Log.d(TAG, "Ad ready from checker!")
                        isLoadingAd = false
                        wasAdReady = true
                        retryCount = 0

                        if (isWaitingForAd) {
                            val uid = auth.currentUser?.uid ?: return@run
                            val today = todayKey()
                            showAdImmediately(uid, today)
                        } else {
                            updateButtonToReady()
                        }
                        return
                    }

                    if (isLoadingAd && !isWaitingForAd) {
                        handler.postDelayed(this, 200)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ad checker error", e)
                }
            }
        }
        handler.post(loadingTimerRunnable!!)
    }

    private fun loadCoins() {
        try {
            val uid = auth.currentUser?.uid ?: return

            val ref = db.child("users").child(uid).child("coins")
            coinsListener = object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    if (!isAdded) return
                    try {
                        val coins = s.getValue(Long::class.java)
                            ?: s.getValue(Int::class.java)?.toLong()
                            ?: 0L
                        binding.txtCoins.text = coins.toString()
                    } catch (e: Exception) {
                        Log.e(TAG, "Coin update error", e)
                    }
                }
                override fun onCancelled(e: DatabaseError) {
                    Log.e(TAG, "Coin listener cancelled", e.toException())
                }
            }
            ref.addValueEventListener(coinsListener!!)

        } catch (e: Exception) {
            Log.e(TAG, "Load coins error", e)
        }
    }

    private fun loadDailyAdsCount() {
        try {
            val uid = auth.currentUser?.uid ?: return
            val today = todayKey()

            val ref = db.child("user_ads").child(uid).child(today)
            adsListener = object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    if (!isAdded) return
                    try {
                        val watched = s.getValue(Int::class.java) ?: 0
                        val left = DAILY_AD_LIMIT - watched
                        binding.txtAdsLeft.text = "Ads left today: $left"

                        if (left <= 0) {
                            binding.btnWatchAd.isEnabled = false
                            binding.btnWatchAd.text = "Daily Limit Reached"
                        } else {
                            if (!isLoadingAd && !isWaitingForAd && !isAdShowing && !RewardedAdManager.isShowing()) {
                                binding.btnWatchAd.isEnabled = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Ad count update error", e)
                    }
                }
                override fun onCancelled(e: DatabaseError) {
                    Log.e(TAG, "Ad count cancelled", e.toException())
                }
            }
            ref.addValueEventListener(adsListener!!)

        } catch (e: Exception) {
            Log.e(TAG, "Load ads count error", e)
        }
    }

    private fun watchAd() {
        try {
            val uid = auth.currentUser?.uid ?: run {
                showToast("Please login first")
                return
            }

            if (isWaitingForAd || isLoadingAd || isAdShowing || RewardedAdManager.isShowing()) {
                showToast("Please wait...")
                return
            }

            val today = todayKey()
            checkDailyLimitBeforeAd(uid, today)

        } catch (e: Exception) {
            Log.e(TAG, "Watch ad error", e)
            showToast("Something went wrong")
        }
    }

    private fun checkDailyLimitBeforeAd(uid: String, today: String) {
        try {
            db.child("user_ads").child(uid).child(today).get()
                .addOnSuccessListener { snap ->
                    if (!isAdded) return@addOnSuccessListener

                    val watched = snap.getValue(Int::class.java) ?: 0
                    if (watched >= DAILY_AD_LIMIT) {
                        showToast("Daily ad limit reached 🚫")
                        binding.btnWatchAd.isEnabled = false
                        binding.btnWatchAd.text = "Daily Limit Reached"
                        return@addOnSuccessListener
                    }

                    if (RewardedAdManager.isAdReady()) {
                        showAdImmediately(uid, today)
                    } else {
                        handleAdNotReady(uid, today)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Limit check failed", e)
                    showToast("Failed to check limit")
                    updateButtonToReady()
                }

        } catch (e: Exception) {
            Log.e(TAG, "Check limit error", e)
            showToast("Error checking limit")
            updateButtonToReady()
        }
    }

    private fun handleAdNotReady(uid: String, today: String) {
        showToast("Preparing ad...")
        isLoadingAd = true
        isWaitingForAd = true
        updateButtonToLoading()
        RewardedAdManager.load(requireContext(), force = true)
        cancelWaitRunnable()

        waitRunnable = object : Runnable {
            var waitTime = 0
            override fun run() {
                try {
                    waitTime += 500
                    if (RewardedAdManager.isAdReady()) {
                        Log.d(TAG, "Ad ready after waiting")
                        isWaitingForAd = false
                        retryCount = 0
                        showAdImmediately(uid, today)
                    } else if (waitTime < 10000) {
                        handler.postDelayed(this, 500)
                    } else {
                        Log.d(TAG, "Ad wait timeout")
                        isWaitingForAd = false
                        isLoadingAd = false

                        if (retryCount < MAX_RETRY) {
                            retryCount++
                            Log.d(TAG, "Retrying load (attempt $retryCount/$MAX_RETRY)")
                            handleAdNotReady(uid, today)
                        } else {
                            showToast(RewardedAdManager.getUserFriendlyError())
                            updateButtonToReady()
                            retryCount = 0
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Wait runnable error", e)
                }
            }
        }
        handler.post(waitRunnable!!)
    }

    private fun showAdImmediately(uid: String, today: String) {
        try {
            cancelWaitRunnable()
            updateButtonToStarting()

            val adsRef = db.child("user_ads").child(uid).child(today)
            val coinsRef = db.child("users").child(uid).child("coins")

            isAdShowing = true
            isLoadingAd = false
            isWaitingForAd = false
            retryCount = 0
            rewardEarned = false  // 🔥 NEW - reset before every ad

            Log.d(TAG, "🎬 Showing ad, isAdShowing = $isAdShowing")

            RewardedAdManager.show(
                activity = requireActivity(),
                onReward = { amount, type ->
                    Log.d(TAG, "Earned $amount $type")
                    rewardEarned = true  // 🔥 NEW - user ne poora ad dekha
                    processReward(uid, today, adsRef, coinsRef)
                },
                onClosed = {
                    Log.d(TAG, "Ad closed callback received")
                    if (!rewardEarned) {  // 🔥 NEW - early exit check
                        showEarlyExitDialog()
                    }
                    handleAdClosed()
                },
                onFailed = { errorMessage ->
                    Log.e(TAG, "Ad failed: $errorMessage")
                    handleAdFailed(errorMessage)
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "Show ad error", e)
            handleAdFailed("Failed to show ad")
        }
    }

    // 🔥 NEW FUNCTION
    private fun showEarlyExitDialog() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("Reward Nahi Mila ❌")
            .setMessage("Poora ad dekho tabhi coin milega.")
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .setCancelable(false)
            .show()
    }

    private fun handleAdClosed() {
        Log.d(TAG, "Ad closed - resetting flags")
        isAdShowing = false
        isLoadingAd = false
        isWaitingForAd = false
        wasAdReady = false
        retryCount = 0
        resetWatchButton()
        handler.postDelayed({
            if (isAdded) {
                preloadAd()
            }
        }, 500)
    }

    private fun handleAdFailed(errorMessage: String) {
        Log.e(TAG, "Ad failed: $errorMessage")
        isAdShowing = false
        isLoadingAd = false
        isWaitingForAd = false
        wasAdReady = false
        resetWatchButton()
        showToast(errorMessage)
        handler.postDelayed({
            if (isAdded) {
                preloadAd()
            }
        }, 1000)
    }

    private fun processReward(
        uid: String,
        today: String,
        adsRef: DatabaseReference,
        coinsRef: DatabaseReference
    ) {
        try {
            val db = FirebaseDatabase.getInstance().reference
            val updates = hashMapOf<String, Any>(
                "user_ads/$uid/$today"    to ServerValue.increment(1),
                "users/$uid/coins"        to ServerValue.increment(1)
            )

            db.updateChildren(updates)
                .addOnSuccessListener {
                    if (!isAdded) return@addOnSuccessListener
                    addTransaction(uid, 1, "Ad Reward", "REWARD")
                    showToast("+1 Coin Earned 🎉")
                    Log.d(TAG, "✅ Reward processed atomically")
                }
                .addOnFailureListener { e ->
                    if (!isAdded) return@addOnFailureListener
                    Log.e(TAG, "Failed to process reward", e)
                    showToast("Failed to add coins, try again")
                }

        } catch (e: Exception) {
            Log.e(TAG, "Process reward error", e)
        }
    }

    private fun resetWatchButton() {
        try {
            binding.btnWatchAd.isEnabled = true
            binding.btnWatchAd.text = "Watch Ad & Earn Coins"
            binding.progressBar.visibility = View.GONE
            isLoadingAd = false
            wasAdReady = false
            isWaitingForAd = false
            loadingTimerRunnable?.let { handler.removeCallbacks(it) }
            loadingTimerRunnable = null
            cancelWaitRunnable()
        } catch (e: Exception) {
            Log.e(TAG, "Reset button error", e)
        }
    }

    private fun checkUpiAndShowWithdrawDialog() {
        try {
            val uid = auth.currentUser?.uid ?: return

            db.child("users").child(uid).child("upiId")
                .get()
                .addOnSuccessListener { upiSnapshot ->
                    if (!isAdded) return@addOnSuccessListener

                    val upiId = upiSnapshot.getValue(String::class.java)
                    if (upiId.isNullOrEmpty()) {
                        showUpiRequiredDialog {
                            checkWithdrawRequests(uid)
                        }
                    } else {
                        checkWithdrawRequests(uid)
                    }
                }
                .addOnFailureListener {
                    showToast("Error checking UPI")
                }

        } catch (e: Exception) {
            Log.e(TAG, "UPI check error", e)
            showToast("Error checking UPI")
        }
    }

    private fun checkWithdrawRequests(uid: String) {
        try {
            db.child("withdraw_requests")
                .orderByChild("uid")
                .equalTo(uid)
                .get()
                .addOnSuccessListener { snap ->
                    if (!isAdded) return@addOnSuccessListener

                    val hasPending = snap.children.any {
                        it.child("status").value == "PENDING"
                    }

                    if (hasPending) {
                        showToast("You already have a pending withdraw ⏳")
                    } else {
                        showWithdrawDialog()
                    }
                }
                .addOnFailureListener {
                    showToast("Failed to check withdraw status")
                }

        } catch (e: Exception) {
            Log.e(TAG, "Withdraw check error", e)
            showToast("Error checking withdraw")
        }
    }

    private fun showUpiRequiredDialog(onUpiSet: () -> Unit) {
        try {
            val view = layoutInflater.inflate(R.layout.dialog_mandatory_upi, null)
            val dialog = AlertDialog.Builder(requireContext())
                .setView(view)
                .setCancelable(false)
                .create()

            val etUpi = view.findViewById<TextInputEditText>(R.id.etUpi)
            val tilUpi = view.findViewById<TextInputLayout>(R.id.tilUpi)
            val btnSet = view.findViewById<TextView>(R.id.btnSet)
            val uid = auth.currentUser?.uid ?: return
            val userRef = db.child("users").child(uid)

            btnSet.setOnClickListener {
                val upi = etUpi.text.toString().trim()

                when {
                    upi.isEmpty() -> tilUpi.error = "UPI ID required for withdraw"
                    !upi.contains("@") -> tilUpi.error = "Invalid UPI format (e.g., user@upi)"
                    else -> {
                        tilUpi.error = null
                        userRef.child("upiId").setValue(upi)
                            .addOnSuccessListener {
                                showToast("UPI ID saved")
                                dialog.dismiss()
                                onUpiSet()
                            }
                            .addOnFailureListener {
                                showToast("Failed to save UPI")
                            }
                    }
                }
            }
            dialog.show()

        } catch (e: Exception) {
            Log.e(TAG, "UPI dialog error", e)
            showToast("Error showing UPI dialog")
        }
    }

    private fun showWithdrawDialog() {
        try {
            val v = layoutInflater.inflate(R.layout.dialog_withdraw, null)
            val dialog = AlertDialog.Builder(requireContext())
                .setView(v)
                .setCancelable(true)
                .create()

            val etAmount = v.findViewById<EditText>(R.id.etAmount)
            val btnWithdraw = v.findViewById<TextView>(R.id.btnWithdraw)
            val btnCancel = v.findViewById<TextView>(R.id.btnCancel)
            val uid = auth.currentUser!!.uid
            val coinsRef = db.child("users").child(uid).child("coins")

            btnCancel.setOnClickListener { dialog.dismiss() }

            btnWithdraw.setOnClickListener {
                val amount = etAmount.text.toString().toIntOrNull() ?: 0

                if (amount < 100) {
                    showToast("Minimum withdraw is 100 coins")
                    return@setOnClickListener
                }

                btnWithdraw.isEnabled = false
                processWithdraw(uid, amount, coinsRef, dialog)
            }
            dialog.show()

        } catch (e: Exception) {
            Log.e(TAG, "Withdraw dialog error", e)
            showToast("Error showing withdraw dialog")
        }
    }

    private fun processWithdraw(uid: String, amount: Int, coinsRef: DatabaseReference, dialog: AlertDialog) {
        coinsRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(d: MutableData): Transaction.Result {
                val current = d.getValue(Int::class.java) ?: 0
                if (current < amount) return Transaction.abort()
                d.value = current - amount
                return Transaction.success(d)
            }

            override fun onComplete(
                e: DatabaseError?,
                committed: Boolean,
                s: DataSnapshot?
            ) {
                if (!isAdded) return

                if (!committed) {
                    showToast("Insufficient coins")
                    return
                }

                createWithdrawRequest(uid, amount)
                addTransaction(uid, -amount, "Withdraw Request", "WITHDRAW")
                dialog.dismiss()
                showToast("Withdraw request submitted ✅")
            }
        })
    }

    private fun createWithdrawRequest(uid: String, amount: Int) {
        try {
            db.child("withdraw_requests").push().setValue(
                mapOf(
                    "uid" to uid,
                    "amount" to amount,
                    "status" to "PENDING",
                    "createdAt" to ServerValue.TIMESTAMP
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Create withdraw error", e)
        }
    }

    private fun addTransaction(
        uid: String,
        amount: Int,
        title: String,
        type: String
    ) {
        try {
            db.child("transactions").child(uid).push().setValue(
                mapOf(
                    "amount" to amount,
                    "title" to title,
                    "type" to type,
                    "time" to ServerValue.TIMESTAMP
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Add transaction error", e)
        }
    }

    private fun todayKey(): String {
        return try {
            val c = Calendar.getInstance()
            "${c.get(Calendar.YEAR)}${c.get(Calendar.DAY_OF_YEAR)}"
        } catch (e: Exception) {
            Log.e(TAG, "Today key error", e)
            "0"
        }
    }

    private fun showToast(message: String) {
        if (isAdded) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
}
