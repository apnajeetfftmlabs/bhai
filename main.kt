package com.appnajeet.user.ui.profile

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.appnajeet.user.R
import com.appnajeet.user.data.model.MyTournament
import com.appnajeet.user.databinding.FragmentMyTournamentsBinding
import com.appnajeet.user.ui.tournament.MyTournamentAdapter
import com.appnajeet.user.ui.tournament.TournamentDetailActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class MyTournamentsFragment : Fragment(R.layout.fragment_my_tournaments) {

    private lateinit var binding: FragmentMyTournamentsBinding
    private val list = mutableListOf<MyTournament>()
    private lateinit var adapter: MyTournamentAdapter
    private val db = FirebaseDatabase.getInstance().reference

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentMyTournamentsBinding.bind(view)

        setupRecyclerView()
        loadMyTournaments()
    }

    private fun setupRecyclerView() {
        // 🔥 FIX 1: Correct constructor - only one parameter
        adapter = MyTournamentAdapter(list)

        // 🔥 Set click listener separately
        adapter.setOnItemClickListener { tournament ->
            openTournamentDetails(tournament)
        }

        binding.recyclerMyTournaments.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerMyTournaments.adapter = adapter
    }

    private fun openTournamentDetails(tournament: MyTournament) {
        // 🔥 FIX 2: Add Intent import
        val intent = Intent(requireContext(), TournamentDetailActivity::class.java)
        intent.putExtra("tournamentId", tournament.tournamentId)
        startActivity(intent)
    }

    private fun loadMyTournaments() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            binding.progressBar.visibility = View.GONE
            binding.txtEmpty.visibility = View.VISIBLE
            binding.txtEmpty.text = "Please login to view your tournaments"
            return
        }

        // Show loading
        binding.progressBar.visibility = View.VISIBLE
        binding.txtEmpty.visibility = View.GONE

        // Load from user_tournaments node
        loadFromUserTournaments(uid)
    }

    private fun loadFromUserTournaments(uid: String) {
        Log.d("MyTournaments", "Loading from user_tournaments for UID: $uid")

        db.child("user_tournaments").child(uid)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    list.clear()

                    if (!snapshot.exists()) {
                        Log.d("MyTournaments", "No data in user_tournaments")
                        loadFromParticipants(uid)
                        return
                    }

                    // 🔥 FIX 3: Proper count handling
                    val totalChildren = snapshot.childrenCount
                    var loadedCount = 0

                    for (tournamentSnap in snapshot.children) {
                        val tournamentId = tournamentSnap.key ?: continue

                        val status = tournamentSnap.child("status").getValue(String::class.java) ?: "JOINED"
                        val name = tournamentSnap.child("name").getValue(String::class.java) ?: "Unknown Tournament"
                        val dateTime = tournamentSnap.child("dateTime").getValue(String::class.java) ?: ""
                        val game = tournamentSnap.child("game").getValue(String::class.java) ?: ""
                        val type = tournamentSnap.child("type").getValue(String::class.java) ?: ""

                        list.add(
                            MyTournament(
                                tournamentId = tournamentId,
                                name = name,
                                status = status,
                                dateTime = dateTime,
                                game = game,
                                type = type
                            )
                        )
                        loadedCount++

                        // Update UI when all are loaded
                        if (loadedCount.toLong() == totalChildren) {
                            updateUI()
                        }
                    }

                    if (totalChildren == 0L) {
                        updateUI()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("MyTournaments", "Error: ${error.message}")
                    loadFromParticipants(uid)
                }
            })
    }

    private fun loadFromParticipants(uid: String) {
        Log.d("MyTournaments", "Loading from tournamentParticipants for UID: $uid")

        db.child("tournamentParticipants")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    list.clear()

                    val tournamentIds = mutableListOf<String>()

                    // Find all tournaments where user is a participant
                    for (tournamentSnap in snapshot.children) {
                        val tournamentId = tournamentSnap.key ?: continue

                        // Check if user is in this tournament
                        if (tournamentSnap.child(uid).exists()) {
                            tournamentIds.add(tournamentId)
                        } else {
                            // Check in team members if any
                            var isTeamMember = false
                            for (participantSnap in tournamentSnap.children) {
                                if (participantSnap.child("members").child(uid).exists()) {
                                    isTeamMember = true
                                    break
                                }
                            }
                            if (isTeamMember) {
                                tournamentIds.add(tournamentId)
                            }
                        }
                    }

                    if (tournamentIds.isEmpty()) {
                        updateUI()
                        return
                    }

                    // Fetch details for each tournament
                    var loadedCount = 0
                    for (tournamentId in tournamentIds) {
                        db.child("tournaments").child(tournamentId).get()
                            .addOnSuccessListener { tournamentDetails ->
                                val name = tournamentDetails.child("name").getValue(String::class.java) ?: "Unknown Tournament"
                                val status = tournamentDetails.child("status").getValue(String::class.java) ?: "UPCOMING"
                                val dateTime = tournamentDetails.child("dateTime").getValue(String::class.java) ?: ""
                                val game = tournamentDetails.child("game").getValue(String::class.java) ?: ""
                                val type = tournamentDetails.child("type").getValue(String::class.java) ?: ""

                                list.add(
                                    MyTournament(
                                        tournamentId = tournamentId,
                                        name = name,
                                        status = status,
                                        dateTime = dateTime,
                                        game = game,
                                        type = type
                                    )
                                )
                                loadedCount++

                                if (loadedCount == tournamentIds.size) {
                                    updateUI()
                                }
                            }
                            .addOnFailureListener {
                                loadedCount++
                                if (loadedCount == tournamentIds.size) {
                                    updateUI()
                                }
                            }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("MyTournaments", "Error in participants: ${error.message}")
                    binding.progressBar.visibility = View.GONE
                    binding.txtEmpty.visibility = View.VISIBLE
                    binding.txtEmpty.text = "Failed to load tournaments"
                }
            })
    }

    private fun updateUI() {
        binding.progressBar.visibility = View.GONE

        if (list.isEmpty()) {
            binding.txtEmpty.visibility = View.VISIBLE
            binding.txtEmpty.text = "No tournaments joined yet"
            binding.recyclerMyTournaments.visibility = View.GONE
        } else {
            binding.txtEmpty.visibility = View.GONE
            binding.recyclerMyTournaments.visibility = View.VISIBLE
            adapter.notifyDataSetChanged()
        }
    }
}
